# Wibes Compiler AI — Isolated Build Backend

This directory provides the FastAPI + PostgreSQL + Redis + Celery + Docker isolated compilation backend consumed by the **Wibes Compiler AI** Android client.

## Endpoints
- `GET /health` — Server and Docker daemon readiness check
- `POST /api/auth/register` — Register user and return JWT Bearer token
- `POST /api/auth/token` — Sign in and obtain JWT Bearer token
- `POST /api/projects/upload` — Upload ZIP archive with ZIP Slip & size limit enforcement
- `POST /api/projects/analyze` — Language, framework, entry point, dependency & output compatibility analysis
- `POST /api/build/start` — Enqueue isolated Docker compilation job via Celery
- `GET /api/build/{build_id}/status` — Poll build stage, progress, and artifact verification status
- `GET /api/build/{build_id}/logs` — Retrieve compilation logs
- `POST /api/build/{build_id}/cancel` — Revoke Celery task and terminate build container
- `GET /api/build/{build_id}/download` — Stream verified output artifact
- `GET /api/projects/history` — List user's projects and latest builds
- `DELETE /api/projects/{project_id}` — Delete project workspace, artifacts, and database metadata

## Build Worker Images
```bash
docker build -f docker/python-builder.Dockerfile -t wibes/python-builder:latest .
docker build -f docker/android-jvm-builder.Dockerfile -t wibes/android-jvm-builder:latest .
docker build -f docker/flutter-builder.Dockerfile -t wibes/flutter-builder:latest .
docker build -f docker/node-capacitor-builder.Dockerfile -t wibes/node-capacitor-builder:latest .
docker build -f docker/cpp-ndk-builder.Dockerfile -t wibes/cpp-ndk-builder:latest .
docker build -f docker/dotnet-builder.Dockerfile -t wibes/dotnet-builder:latest .
docker build -f docker/web-php-builder.Dockerfile -t wibes/web-php-builder:latest .
```

## Run Stack
```bash
export JWT_SECRET_KEY="$(openssl rand -hex 32)"
docker compose up --build
```
Connect the Android client in the **Server** tab using `http://10.0.2.2:8000` (emulator loopback) or your HTTPS server URL.
