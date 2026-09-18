"""Runtime configuration for the DotHeart widget backend.

All settings are sourced from environment variables so the same image can
run unmodified in local dev and on Render.
"""
from __future__ import annotations

import os
from pathlib import Path


class ConfigError(RuntimeError):
    """Raised when required configuration is missing or invalid."""


def _require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ConfigError(
            f"Required environment variable '{name}' is not set. "
            "Refusing to start without it."
        )
    return value


class Settings:
    """Process-wide settings, resolved once at import time."""

    def __init__(self) -> None:
        # Shared secret the Android clients must present to POST updates.
        self.widget_token: str = _require_env("WIDGET_TOKEN")

        # Root directory for all persisted state (mount Render's persistent
        # disk here in production, e.g. "/var/data").
        self.data_dir: Path = Path(os.environ.get("DATA_DIR", "./data")).resolve()
        self.image_dir: Path = self.data_dir / "images"
        self.db_path: Path = self.data_dir / "widget.db"

        # Upload limits / validation.
        self.max_image_bytes: int = int(
            os.environ.get("MAX_IMAGE_BYTES", str(2 * 1024 * 1024))
        )
        self.max_message_length: int = int(os.environ.get("MAX_MESSAGE_LENGTH", "140"))

        # Networking.
        self.port: int = int(os.environ.get("PORT", "8000"))
        self.log_level: str = os.environ.get("LOG_LEVEL", "INFO").upper()

    def ensure_directories(self) -> None:
        self.image_dir.mkdir(parents=True, exist_ok=True)


settings = Settings()
