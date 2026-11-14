# Backend image: the API, the background worker and the migration job.
# Build from the repository root:  docker build -f backend/Dockerfile backend
FROM python:3.12-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Tesseract for receipt OCR, with Bangla and English data. tzdata for Asia/Dhaka.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-ben tesseract-ocr-eng tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
# Exact versions with hashes, exported from uv.lock (a test keeps them in sync).
COPY requirements.txt .
RUN pip install --require-hashes -r requirements.txt

COPY alembic.ini ./
COPY alembic ./alembic
COPY app ./app

# Never run as root. Receipts live in a mounted volume owned by this user.
RUN groupadd --system --gid 10001 fet \
    && useradd --system --uid 10001 --gid fet --home /app --shell /usr/sbin/nologin fet \
    && mkdir -p /data/receipts && chown fet:fet /data/receipts
USER fet

ENV FET_ENVIRONMENT=production \
    FET_RECEIPTS_DIR=/data/receipts

EXPOSE 8000
# One process: the login rate limiter is in memory (docs/security.md, S5).
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*", "--no-access-log"]
