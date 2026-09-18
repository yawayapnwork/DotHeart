"""Persistence layer: SQLite for widget state, filesystem for the image.

A single logical "current state" row is kept in SQLite. Image bytes live on
disk as a file whose name is derived from its sniffed format. All writes
(file and DB) are performed so that a concurrent reader never observes a
half-written file or an inconsistent (filename, checksum) pair.
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
    timestamp INTEGER NOT NULL
);
"""


@dataclass(frozen=True)
class WidgetState:
    message: str
    image_filename: str
    checksum: str
    timestamp: int


class WidgetStorage:
    """Thread-safe façade over the SQLite state row and image file writes.

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
            self._conn.execute(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._conn.close()

    def get_current_state(self) -> Optional[WidgetState]:
        with self._lock:
            row = self._conn.execute(
                "SELECT message, image_filename, checksum, timestamp "
                "FROM widget_state WHERE id = 1"
            ).fetchone()
        if row is None:
            return None
        return WidgetState(
            message=row[0], image_filename=row[1], checksum=row[2], timestamp=row[3]
        )

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

            timestamp = int(time.time())
            with self._conn:
                self._conn.execute(
                    """
                    INSERT INTO widget_state (id, message, image_filename, checksum, timestamp)
                    VALUES (1, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        message = excluded.message,
                        image_filename = excluded.image_filename,
                        checksum = excluded.checksum,
                        timestamp = excluded.timestamp
                    """,
                    (message, filename, checksum, timestamp),
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
            message=message, image_filename=filename, checksum=checksum, timestamp=timestamp
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
