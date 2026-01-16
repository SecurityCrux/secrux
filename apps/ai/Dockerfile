# syntax=docker/dockerfile:1.6

FROM python:3.11-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

COPY pyproject.toml /app/pyproject.toml
COPY README.md /app/README.md

COPY secrux_ai /app/secrux_ai
COPY service /app/service

RUN pip install --no-cache-dir .

EXPOSE 5156

CMD ["uvicorn", "service.main:app", "--host", "0.0.0.0", "--port", "5156"]

