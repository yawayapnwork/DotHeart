# ---- Builder stage -----------------------------------------------------
FROM python:3.12-slim AS builder

WORKDIR /build

RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY requirements.txt .
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# ---- Runtime stage -------------------------------------------------------
FROM python:3.12-slim AS runtime

# Create a dedicated, unprivileged user and the persistent data directory
# up front so ownership is correct before we drop root.
RUN groupadd --system dotheart && \
    useradd --system --gid dotheart --home-dir /app --shell /usr/sbin/nologin dotheart && \
    mkdir -p /app /var/data/images && \
    chown -R dotheart:dotheart /app /var/data

COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DATA_DIR=/var/data \
    PORT=8000

WORKDIR /app
COPY --chown=dotheart:dotheart app ./app

USER dotheart

EXPOSE 8000

# Single worker keeps memory footprint low and is correct here: state is
# guarded by an in-process lock (app/storage.py), so a multi-worker deploy
# would need a different concurrency strategy anyway. --limit-max-requests
# is omitted since the container is expected to run indefinitely on Render.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --workers 1 --timeout-keep-alive 15 --no-access-log"]
