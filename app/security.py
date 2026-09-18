"""Security-sensitive helpers: token verification, image and message validation.

Kept isolated from request-handling code so each rule can be unit tested and
audited independently.
"""
from __future__ import annotations

import secrets
import unicodedata
from dataclasses import dataclass


class InvalidImageError(ValueError):
    """Raised when uploaded bytes do not pass magic-byte / size validation."""


class InvalidMessageError(ValueError):
    """Raised when the supplied message fails sanitization rules."""


@dataclass(frozen=True)
class ImageFormat:
    name: str
    extension: str
    content_type: str


# Magic-byte signatures for the only formats we accept. Extensions supplied
# by the client are never trusted -- the on-disk name is always derived from
# the sniffed signature below.
_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
_JPEG_SIGNATURE = b"\xff\xd8\xff"
_GIF_SIGNATURES = (b"GIF87a", b"GIF89a")


def detect_image_format(data: bytes) -> ImageFormat:
    """Sniff the real image format from magic bytes.

    Raises InvalidImageError if the content does not match any of the
    formats we support (PNG, JPEG, GIF). File extensions are never trusted.
    """
    if data.startswith(_PNG_SIGNATURE):
        return ImageFormat("PNG", "png", "image/png")
    if data.startswith(_JPEG_SIGNATURE):
        return ImageFormat("JPEG", "jpg", "image/jpeg")
    if data.startswith(_GIF_SIGNATURES):
        return ImageFormat("GIF", "gif", "image/gif")
    raise InvalidImageError("Unrecognized or unsupported image format.")


def validate_image_bytes(data: bytes, max_bytes: int) -> ImageFormat:
    """Validate size and magic bytes, returning the detected format.

    Raises InvalidImageError on any violation.
    """
    if not data:
        raise InvalidImageError("Uploaded file is empty.")
    if len(data) > max_bytes:
        raise InvalidImageError(
            f"Image exceeds maximum allowed size of {max_bytes} bytes."
        )
    return detect_image_format(data)


def sanitize_message(raw: str, max_length: int) -> str:
    """Strip null bytes and control characters, then enforce a length cap.

    Printable whitespace (space) is preserved; all Unicode control /
    formatting characters (category starting with 'C') are dropped, which
    also removes null bytes, ANSI escape sequences, and similar payloads.
    """
    if raw is None:
        raise InvalidMessageError("Message is required.")

    cleaned_chars = [
        ch
        for ch in raw
        if ch == " " or not unicodedata.category(ch).startswith("C")
    ]
    cleaned = "".join(cleaned_chars).strip()

    if not cleaned:
        raise InvalidMessageError("Message must not be empty after sanitization.")
    if len(cleaned) > max_length:
        cleaned = cleaned[:max_length]
    return cleaned


def verify_token(provided: str, expected: str) -> bool:
    """Constant-time token comparison to prevent timing side channels."""
    if provided is None:
        return False
    return secrets.compare_digest(provided.encode("utf-8"), expected.encode("utf-8"))
