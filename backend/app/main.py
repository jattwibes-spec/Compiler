import os
import shutil
import uuid
from pathlib import Path
from typing import List, Optional

import docker
from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile, status
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from .analyzer import COMPATIBILITY_MATRIX, UNSUPPORTED_REASONS, analyze_workspace_dir
from .celery_app import celery_app
from .models import BuildJob, Project, User, init_db
from .security import (
    MAX_UPLOAD_BYTES,
    compute_sha256,
    create_access_token,
    get_current_user,
    get_db,
    hash_password,
    safe_extract_zip,
    verify_password,
)
from .tasks import run_isolated_build

PROJECTS_ROOT = Path(os.getenv("PROJECTS_ROOT", "/data/projects"))
PROJECTS_ROOT.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="Wibes Compiler AI Build Backend", version="1.0.0")


@app.on_event("startup")
def on_startup():
    init_db()


class AuthRequest(BaseModel):
    username: str
    password: str


class AnalyzeRequest(BaseModel):
    project_id: str


class StartBuildRequest(BaseModel):
    project_id: str
    app_name: str
    package_name: str
    version_name: str = "1.0.0"
    version_code: int = 1
    min_sdk: int = 26
    target_sdk: int = 35
    output_format: str
    build_mode: str = "debug"


@app.get("/health")
def health():
    docker_ok = False
    try:
        docker.from_env().ping()
        docker_ok = True
    except Exception:
        docker_ok = False
    return {
        "status": "ok",
        "docker_available": docker_ok,
        "active_workers": 2,
        "version": "1.0.0",
    }


@app.post("/api/auth/register")
def register(payload: AuthRequest, db: Session = Depends(get_db)):
    if db.query(User).filter(User.username == payload.username).first():
        raise HTTPException(status_code=400, detail="Username already exists")
    user = User(username=payload.username, password_hash=hash_password(payload.password))
    db.add(user)
    db.commit()
    token = create_access_token(user.username)
    return {"access_token": token, "token_type": "bearer", "username": user.username}


@app.post("/api/auth/token")
def login(payload: AuthRequest, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == payload.username).first()
    if not user or not verify_password(payload.password, user.password_hash):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")
    token = create_access_token(user.username)
    return {"access_token": token, "token_type": "bearer", "username": user.username}


@app.post("/api/projects/upload")
async def upload_project(
    file: UploadFile = File(...),
    project_name: str = Form("UploadedProject"),
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    project_id = str(uuid.uuid4())
    proj_dir = PROJECTS_ROOT / project_id
    proj_dir.mkdir(parents=True, exist_ok=True)
    archive_path = proj_dir / "source.zip"
    workspace_dir = proj_dir / "workspace"

    total_written = 0
    with open(archive_path, "wb") as out:
        while chunk := await file.read(65536):
            total_written += len(chunk)
            if total_written > MAX_UPLOAD_BYTES:
                shutil.rmtree(proj_dir, ignore_errors=True)
                raise HTTPException(status_code=413, detail="Upload exceeds server max size limit")
            out.write(chunk)

    try:
        file_count = safe_extract_zip(archive_path, workspace_dir)
    except Exception as exc:
        shutil.rmtree(proj_dir, ignore_errors=True)
        raise HTTPException(status_code=400, detail=f"Security scan rejected archive: {exc}") from exc

    sha = compute_sha256(archive_path)
    analysis = analyze_workspace_dir(workspace_dir)

    project = Project(
        id=project_id,
        owner_id=user.id,
        name=project_name,
        workspace_dir=str(workspace_dir),
        archive_path=str(archive_path),
        size_bytes=total_written,
        sha256=sha,
        file_count=file_count,
        language=analysis["language"],
        framework=analysis["framework"],
        entry_point=analysis["entry_point"],
        security_passed=True,
    )
    db.add(project)
    db.commit()

    return {
        "project_id": project_id,
        "name": project_name,
        "size_bytes": total_written,
        "sha256": sha,
        "file_count": file_count,
        "security_passed": True,
    }


@app.post("/api/projects/analyze")
def analyze_project(
    payload: AnalyzeRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    project = db.query(Project).filter(Project.id == payload.project_id, Project.owner_id == user.id).first()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    result = analyze_workspace_dir(Path(project.workspace_dir))
    project.language = result["language"]
    project.framework = result["framework"]
    project.entry_point = result["entry_point"]
    db.commit()
    return {"project_id": project.id, **result}


@app.post("/api/build/start")
def start_build(
    payload: StartBuildRequest,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    project = db.query(Project).filter(Project.id == payload.project_id, Project.owner_id == user.id).first()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")

    compatible = COMPATIBILITY_MATRIX.get(project.language, [])
    if payload.output_format not in compatible:
        reason = UNSUPPORTED_REASONS.get(
            (project.language, payload.output_format),
            f"Output format {payload.output_format} is not supported for {project.language}",
        )
        raise HTTPException(status_code=400, detail=reason)

    build_id = str(uuid.uuid4())
    job = BuildJob(
        id=build_id,
        project_id=project.id,
        owner_id=user.id,
        app_name=payload.app_name,
        package_name=payload.package_name,
        version_name=payload.version_name,
        version_code=payload.version_code,
        min_sdk=payload.min_sdk,
        target_sdk=payload.target_sdk,
        output_format=payload.output_format,
        build_mode=payload.build_mode,
        stage="QUEUED",
        progress_percent=5,
        dependency_status="Queued for isolated container",
        logs=f"[INFO] Build {build_id} queued for {project.language} -> {payload.output_format}",
    )
    db.add(job)
    db.commit()

    run_isolated_build.delay(build_id)
    return {"build_id": build_id, "stage": "QUEUED", "message": "Isolated Docker build enqueued."}


@app.get("/api/build/{build_id}/status")
def get_build_status(
    build_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    job = db.query(BuildJob).filter(BuildJob.id == build_id, BuildJob.owner_id == user.id).first()
    if not job:
        raise HTTPException(status_code=404, detail="Build job not found")
    artifact_exists = bool(job.artifact_path and Path(job.artifact_path).is_file() and Path(job.artifact_path).stat().st_size > 0)
    return {
        "build_id": job.id,
        "project_id": job.project_id,
        "stage": job.stage,
        "progress_percent": job.progress_percent,
        "dependency_status": job.dependency_status,
        "error_message": job.error_message,
        "artifact_ready": job.stage == "COMPLETED" and artifact_exists,
        "artifact_filename": job.artifact_filename,
        "artifact_size_bytes": job.artifact_size_bytes,
        "artifact_sha256": job.artifact_sha256,
        "duration_seconds": float(job.duration_seconds),
    }


@app.get("/api/build/{build_id}/logs")
def get_build_logs(
    build_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    job = db.query(BuildJob).filter(BuildJob.id == build_id, BuildJob.owner_id == user.id).first()
    if not job:
        raise HTTPException(status_code=404, detail="Build job not found")
    return {
        "build_id": job.id,
        "logs": job.logs.splitlines(),
        "stage": job.stage,
    }


@app.post("/api/build/{build_id}/cancel")
def cancel_build(
    build_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    job = db.query(BuildJob).filter(BuildJob.id == build_id, BuildJob.owner_id == user.id).first()
    if not job:
        raise HTTPException(status_code=404, detail="Build job not found")
    if job.celery_task_id:
        celery_app.control.revoke(job.celery_task_id, terminate=True)
    if job.container_name:
        try:
            docker.from_env().containers.get(job.container_name).remove(force=True)
        except Exception:
            pass
    job.stage = "CANCELLED"
    job.error_message = "Cancelled by user"
    db.commit()
    return {"build_id": build_id, "cancelled": True, "message": "Build cancelled."}


@app.get("/api/build/{build_id}/download")
def download_artifact(
    build_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    job = db.query(BuildJob).filter(BuildJob.id == build_id, BuildJob.owner_id == user.id).first()
    if not job or not job.artifact_path or not Path(job.artifact_path).is_file():
        raise HTTPException(status_code=404, detail="Verified build artifact does not exist")
    return FileResponse(
        path=job.artifact_path,
        filename=job.artifact_filename or f"output_{build_id[:8]}",
        media_type="application/octet-stream",
    )


@app.get("/api/projects/history")
def get_project_history(
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    projects: List[Project] = (
        db.query(Project)
        .filter(Project.owner_id == user.id)
        .order_by(Project.created_at.desc())
        .all()
    )
    items = []
    for p in projects:
        latest = p.builds[-1] if p.builds else None
        items.append({
            "project_id": p.id,
            "name": p.name,
            "language": p.language,
            "framework": p.framework,
            "created_at": p.created_at.isoformat(),
            "latest_build_id": latest.id if latest else None,
            "latest_build_status": latest.stage if latest else None,
        })
    return items


@app.delete("/api/projects/{project_id}")
def delete_project(
    project_id: str,
    user: User = Depends(get_current_user),
    db: Session = Depends(get_db),
):
    project = db.query(Project).filter(Project.id == project_id, Project.owner_id == user.id).first()
    if not project:
        raise HTTPException(status_code=404, detail="Project not found")
    for build in project.builds:
        if build.artifact_path:
            shutil.rmtree(Path(build.artifact_path).parent, ignore_errors=True)
    shutil.rmtree(Path(project.workspace_dir).parent, ignore_errors=True)
    db.delete(project)
    db.commit()
    return {"project_id": project_id, "deleted": True}
