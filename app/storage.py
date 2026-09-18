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
    last_ping_b INTEGER NOT NULL DEFAULT 0
);
"""

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
            self._conn.execute(
                "INSERT OR IGNORE INTO ping_state (id, last_ping_a, last_ping_b) "
                "VALUES (1, 0, 0)"
            )

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
                "SELECT last_ping_a, last_ping_b FROM ping_state WHERE id = 1"
            ).fetchone()
        if row is None:
            return PingState(last_ping_a=0, last_ping_b=0)
        return PingState(last_ping_a=row[0], last_ping_b=row[1])

    def record_ping(self, user_id: str) -> int:
        """Atomically stamps last_ping_<user_id> with the current time and
        returns that timestamp. Raises ValueError for any user_id other than
        "a" or "b" -- callers should validate before calling this, but this
        is the last line of defense against a malformed/unvalidated column
        name reaching raw SQL.
        """
        if user_id not in VALID_USER_IDS:
            raise ValueError(f"Invalid user_id: {user_id!r}")

        column = "last_ping_a" if user_id == "a" else "last_ping_b"
        timestamp = int(time.time())
        with self._lock:
            with self._conn:
                # column name is interpolated from the fixed VALID_USER_IDS
                # check above, never from unvalidated input, so this is not
                # a SQL-injection path despite the f-string.
                self._conn.execute(
                    f"UPDATE ping_state SET {column} = ? WHERE id = 1", (timestamp,)
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
