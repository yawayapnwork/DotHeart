"""DotHeart couple-widget backend.

Endpoints:
  POST /api/v1/widget/update  -- upload a new image + status message
  POST /api/v1/widget/ping    -- record a user's presence ping
  GET  /api/v1/widget/current -- fetch the latest state as JSON
  GET  /static/{filename}     -- serve the current image bytes

Designed to run as a single uvicorn worker inside a 512 MB container, with
all state on local/persistent disk (SQLite + filesystem, no external DB).
"""
from __future__ import annotations

import logging
import sys
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, File, Form, HTTPException, Query, Request, UploadFile, status
from fastapi.responses import FileResponse, JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.config import settings
from app.security import (
    InvalidImageError,
    InvalidMessageError,
    sanitize_message,
    validate_image_bytes,
    verify_token,
)
from app.storage import VALID_USER_IDS, WidgetStorage

logging.basicConfig(
    level=settings.log_level,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
    stream=sys.stdout,
)
logger = logging.getLogger("dotheart.api")

storage: WidgetStorage | None = None

_bearer_scheme = HTTPBearer(auto_error=False)


@asynccontextmanager
async def lifespan(app: FastAPI):
    global storage
    settings.ensure_directories()
    storage = WidgetStorage(db_path=settings.db_path, image_dir=settings.image_dir)
    logger.info("DotHeart backend started. data_dir=%s", settings.data_dir)
    try:
        yield
    finally:
        storage.close()
        logger.info("DotHeart backend shut down.")


app = FastAPI(title="DotHeart Widget Backend", version="1.0.0", lifespan=lifespan)


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Never leak raw tracebacks; log full detail server-side only."""
    logger.error(
        "Unhandled exception on %s %s", request.method, request.url.path, exc_info=exc
    )
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content={"detail": "Internal server error."},
    )


@app.exception_handler(StarletteHTTPException)
async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    if exc.status_code >= 500:
        logger.error(
            "HTTP %s on %s %s: %s",
            exc.status_code,
            request.method,
            request.url.path,
            exc.detail,
        )
    return JSONResponse(status_code=exc.status_code, content={"detail": exc.detail})


async def _read_upload_limited(upload: UploadFile, max_bytes: int) -> bytes:
    """Read an UploadFile in chunks, aborting as soon as the size cap is
    exceeded, so an oversized upload never fully materializes in memory.
    """
    chunks: list[bytes] = []
    total = 0
    chunk_size = 64 * 1024
    while True:
        chunk = await upload.read(chunk_size)
        if not chunk:
            break
        total += len(chunk)
        if total > max_bytes:
            raise InvalidImageError(
                f"Image exceeds maximum allowed size of {max_bytes} bytes."
            )
        chunks.append(chunk)
    return b"".join(chunks)


def _require_bearer_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> None:
    """Shared dependency for header/bearer-authenticated endpoints (distinct
    from /update's multipart form-field token, which predates this and is
    left as-is for backward compatibility with the existing CLI).
    """
    if credentials is None or not verify_token(credentials.credentials, settings.widget_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token.")


class PingRequest(BaseModel):
    user_id: str
    # Optional device power telemetry; both must be present to be stored.
    battery_level: int | None = Field(default=None, ge=0, le=100)
    is_charging: bool | None = None


@app.post("/api/v1/widget/update")
async def update_widget(
    token: str = Form(...),
    message: str = Form(...),
    file: UploadFile = File(...),
) -> JSONResponse:
    if not verify_token(token, settings.widget_token):
        logger.warning("Rejected update: invalid token.")
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token.")

    try:
        clean_message = sanitize_message(message, settings.max_message_length)
    except InvalidMessageError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc

    try:
        image_bytes = await _read_upload_limited(file, settings.max_image_bytes)
        image_format = validate_image_bytes(image_bytes, settings.max_image_bytes)
    except InvalidImageError as exc:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(exc)) from exc
    finally:
        await file.close()

    assert storage is not None
    state = storage.save_update(
        message=clean_message, image_bytes=image_bytes, extension=image_format.extension
    )

    logger.info(
        "Widget updated: format=%s size=%d checksum=%s",
        image_format.name,
        len(image_bytes),
        state.checksum,
    )

    return JSONResponse(
        status_code=status.HTTP_200_OK,
        content={
            "message": state.message,
            "image_url": f"/static/{state.image_filename}",
            "timestamp": state.last_art_updated_at,
            "checksum": state.checksum,
        },
    )


@app.post("/api/v1/widget/ping", dependencies=[Depends(_require_bearer_token)])
async def ping_widget(payload: PingRequest) -> JSONResponse:
    if payload.user_id not in VALID_USER_IDS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"user_id must be one of {VALID_USER_IDS!r}.",
        )

    assert storage is not None
    ping_timestamp = storage.record_ping(
        payload.user_id,
        battery_level=payload.battery_level,
        is_charging=payload.is_charging,
    )

    logger.info("Ping recorded: user_id=%s timestamp=%d", payload.user_id, ping_timestamp)

    return JSONResponse(
        status_code=status.HTTP_200_OK,
        content={"user_id": payload.user_id, "timestamp": ping_timestamp},
    )


@app.get("/api/v1/widget/current")
async def get_current_widget(user_id: str | None = Query(default=None)) -> JSONResponse:
    """`user_id` ("a"/"b") identifies the requester so the peer's battery
    state can be returned; without it peer_* fields are null.
    """
    if user_id is not None and user_id not in VALID_USER_IDS:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"user_id must be one of {VALID_USER_IDS!r}.",
        )
    assert storage is not None
    state = storage.get_current_state()
    if state is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="No widget state has been uploaded yet."
        )
    ping = storage.get_ping_state()
    peer_level: int | None = None
    peer_charging: bool | None = None
    if user_id is not None:
        peer_level, peer_charging = ping.battery_for("b" if user_id == "a" else "a")
    return JSONResponse(
        status_code=status.HTTP_200_OK,
        content={
            "message": state.message,
            "image_url": f"/static/{state.image_filename}",
            "timestamp": state.last_art_updated_at,
            "checksum": state.checksum,
            "last_ping_a": ping.last_ping_a,
            "last_ping_b": ping.last_ping_b,
            "peer_battery_level": peer_level,
            "peer_is_charging": peer_charging,
            "notes": storage.get_recent_notes(),
        },
    )


@app.get("/static/{filename}")
async def get_static_image(filename: str) -> FileResponse:
    assert storage is not None
    path = storage.image_path_for(filename)
    if path is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Image not found.")

    media_type = "image/png"
    if filename.endswith(".jpg"):
        media_type = "image/jpeg"
    elif filename.endswith(".gif"):
        media_type = "image/gif"

    return FileResponse(
        path=path,
        media_type=media_type,
        headers={"Cache-Control": "no-cache, max-age=0, must-revalidate"},
    )


@app.get("/health")
async def health() -> JSONResponse:
    return JSONResponse(status_code=status.HTTP_200_OK, content={"status": "ok"})
