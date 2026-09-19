"""Persistence layer: SQLite for widget/ping state, filesystem for the image.

Two independent single-row tables:
  widget_state -- the current art + note (absent until the first push;
                  GET /api/v1/widget/current 404s until then).
  ping_state   -- last-ping timestamps for both users, always present from
                  startup (id=1 row created unconditionally) so /ping works
                  even before any art has ever been pushed.

Image bytes live on disk as a file whose name is derived from its sniffed
format. All writes (file and DB) are performed so that a concurrent reader
never observes a half-written file or an inconsistent (filename, checksum)
pair.
"""
from __future__ import annotations

import hashlib
import logging
import os
import sqlite3
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

logger = logging.getLogger("dotheart.storage")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS widget_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    message TEXT NOT NULL,
    image_filename TEXT NOT NULL,
    checksum TEXT NOT NULL,
    last_art_updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ping_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    last_ping_a INTEGER NOT NULL DEFAULT 0,
    last_ping_b INTEGER NOT NULL DEFAULT 0,
    battery_level_a INTEGER NOT NULL DEFAULT -1,
    is_charging_a INTEGER NOT NULL DEFAULT 0,
    battery_level_b INTEGER NOT NULL DEFAULT -1,
    is_charging_b INTEGER NOT NULL DEFAULT 0
);
"""

# Columns added after the first release; applied to pre-existing databases
# by WidgetStorage._migrate_ping_state (CREATE TABLE IF NOT EXISTS above is a
# no-op for a table that already exists). battery_level -1 == "never reported".
_PING_STATE_MIGRATION_COLUMNS = (
    ("battery_level_a", "INTEGER NOT NULL DEFAULT -1"),
    ("is_charging_a", "INTEGER NOT NULL DEFAULT 0"),
    ("battery_level_b", "INTEGER NOT NULL DEFAULT -1"),
    ("is_charging_b", "INTEGER NOT NULL DEFAULT 0"),
)

VALID_USER_IDS = ("a", "b")


@dataclass(frozen=True)
class WidgetState:
    message: str
    image_filename: str
    checksum: str
    last_art_updated_at: int


@dataclass(frozen=True)
class PingState:
    last_ping_a: int
    last_ping_b: int
    battery_level_a: int = -1
    is_charging_a: bool = False
    battery_level_b: int = -1
    is_charging_b: bool = False

    def battery_for(self, user_id: str) -> tuple[Optional[int], Optional[bool]]:
        """(level, is_charging) for one user, or (None, None) if that user has
        never reported a battery reading."""
        if user_id == "a":
            level, charging = self.battery_level_a, self.is_charging_a
        elif user_id == "b":
            level, charging = self.battery_level_b, self.is_charging_b
        else:
            raise ValueError(f"Invalid user_id: {user_id!r}")
        if level < 0:
            return None, None
        return level, charging


class WidgetStorage:
    """Thread-safe façade over the SQLite state rows and image file writes.

    A single :class:`sqlite3.Connection` is shared across requests (the
    container runs a single uvicorn worker), guarded by a lock since
    sqlite3 connections are not safe for concurrent use from multiple
    threads without external synchronization.
    """

    def __init__(self, db_path: Path, image_dir: Path) -> None:
        self._db_path = db_path
        self._image_dir = image_dir
        self._lock = threading.RLock()
        self._conn = sqlite3.connect(str(db_path), check_same_thread=False)
        self._conn.execute("PRAGMA journal_mode=WAL;")
        with self._conn:
            # executescript, not execute: _SCHEMA contains two CREATE TABLE
            # statements, and sqlite3.Connection.execute() accepts only a
            # single statement per call.
            self._conn.executescript(_SCHEMA)
            self._migrate_ping_state()
            self._conn.execute(
                "INSERT OR IGNORE INTO ping_state (id, last_ping_a, last_ping_b) "
                "VALUES (1, 0, 0)"
            )

    def _migrate_ping_state(self) -> None:
        existing = {row[1] for row in self._conn.execute("PRAGMA table_info(ping_state)")}
        for name, ddl in _PING_STATE_MIGRATION_COLUMNS:
            if name not in existing:
                self._conn.execute(f"ALTER TABLE ping_state ADD COLUMN {name} {ddl}")

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def get_current_state(self) -> Optional[WidgetState]:
        with self._lock:
            row = self._conn.execute(
                "SELECT message, image_filename, checksum, last_art_updated_at "
                "FROM widget_state WHERE id = 1"
            ).fetchone()
        if row is None:
            return None
        return WidgetState(
            message=row[0], image_filename=row[1], checksum=row[2], last_art_updated_at=row[3]
        )

    def get_ping_state(self) -> PingState:
        with self._lock:
            row = self._conn.execute(
                "SELECT last_ping_a, last_ping_b, battery_level_a, is_charging_a, "
                "battery_level_b, is_charging_b FROM ping_state WHERE id = 1"
            ).fetchone()
        if row is None:
            return PingState(last_ping_a=0, last_ping_b=0)
        return PingState(
            last_ping_a=row[0],
            last_ping_b=row[1],
            battery_level_a=row[2],
            is_charging_a=bool(row[3]),
            battery_level_b=row[4],
            is_charging_b=bool(row[5]),
        )

    def record_ping(
        self,
        user_id: str,
        battery_level: Optional[int] = None,
        is_charging: Optional[bool] = None,
    ) -> int:
        """Atomically stamps last_ping_<user_id> with the current time and
        returns that timestamp. If both battery_level (0-100) and is_charging
        are given, they are persisted in the same UPDATE, so a reader never
        sees a new ping time paired with a stale battery reading. A partial
        reading (only one of the two) is ignored rather than half-applied.

        Raises ValueError for any user_id other than "a" or "b" or an
        out-of-range battery_level -- callers should validate before calling
        this, but this is the last line of defense against a malformed/
        unvalidated column name reaching raw SQL.
        """
        if user_id not in VALID_USER_IDS:
            raise ValueError(f"Invalid user_id: {user_id!r}")
        if battery_level is not None and not 0 <= battery_level <= 100:
            raise ValueError(f"Invalid battery_level: {battery_level!r}")

        suffix = user_id  # "a" or "b", validated above
        timestamp = int(time.time())
        with self._lock:
            with self._conn:
                # column names are interpolated from the fixed VALID_USER_IDS
                # check above, never from unvalidated input, so this is not
                # a SQL-injection path despite the f-strings.
                if battery_level is not None and is_charging is not None:
                    self._conn.execute(
                        f"UPDATE ping_state SET last_ping_{suffix} = ?, "
                        f"battery_level_{suffix} = ?, is_charging_{suffix} = ? WHERE id = 1",
                        (timestamp, battery_level, 1 if is_charging else 0),
                    )
                else:
                    self._conn.execute(
                        f"UPDATE ping_state SET last_ping_{suffix} = ? WHERE id = 1",
                        (timestamp,),
                    )
        return timestamp

    def save_update(self, message: str, image_bytes: bytes, extension: str) -> WidgetState:
        """Atomically persist a new image + message as the current state.

        Steps:
          1. Compute checksum and target filename from the sniffed format.
          2. Write image bytes to a temp file in the same directory, fsync,
             then os.replace onto the target path (atomic on POSIX and NTFS).
          3. Upsert the SQLite row inside a transaction.
          4. Best-effort cleanup of a stale image file left over from a
             previous format (e.g. old .png when new upload is .gif).

        The lock serializes updates; readers use WAL mode so GET requests
        are never blocked by a concurrent write.
        """
        checksum = hashlib.sha256(image_bytes).hexdigest()
        filename = f"widget_image.{extension}"
        target_path = self._image_dir / filename

        with self._lock:
            previous = self.get_current_state()
            self._atomic_write(target_path, image_bytes)

            last_art_updated_at = int(time.time())
            with self._conn:
                self._conn.execute(
                    """
                    INSERT INTO widget_state (id, message, image_filename, checksum, last_art_updated_at)
                    VALUES (1, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        message = excluded.message,
                        image_filename = excluded.image_filename,
                        checksum = excluded.checksum,
                        last_art_updated_at = excluded.last_art_updated_at
                    """,
                    (message, filename, checksum, last_art_updated_at),
                )

            if previous is not None and previous.image_filename != filename:
                stale_path = self._image_dir / previous.image_filename
                try:
                    if stale_path.exists():
                        stale_path.unlink()
                except OSError:
                    logger.warning(
                        "Failed to remove stale image file %s", stale_path, exc_info=True
                    )

        return WidgetState(
            message=message,
            image_filename=filename,
            checksum=checksum,
            last_art_updated_at=last_art_updated_at,
        )

    def image_path_for(self, filename: str) -> Optional[Path]:
        """Resolve a requested filename to a path, only if it is the
        currently active image. Prevents path traversal and serving of
        arbitrary/orphaned files.
        """
        current = self.get_current_state()
        if current is None or current.image_filename != filename:
            return None
        path = self._image_dir / filename
        try:
            resolved = path.resolve()
            resolved.relative_to(self._image_dir.resolve())
        except (OSError, ValueError):
            return None
        if not resolved.is_file():
            return None
        return resolved

    @staticmethod
    def _atomic_write(target_path: Path, data: bytes) -> None:
        target_path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(
            dir=str(target_path.parent), prefix=".tmp_upload_", suffix=".part"
        )
        try:
            with os.fdopen(fd, "wb") as tmp_file:
                tmp_file.write(data)
                tmp_file.flush()
                os.fsync(tmp_file.fileno())
            os.replace(tmp_name, target_path)
        except BaseException:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise
