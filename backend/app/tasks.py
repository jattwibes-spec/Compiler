import os
import shutil
import time
from datetime import datetime, timezone
from pathlib import Path

import docker

from .celery_app import celery_app
from .models import BuildJob, Project, SessionLocal
from .security import compute_sha256

ARTIFACTS_ROOT = Path(os.getenv("ARTIFACTS_ROOT", "/data/artifacts"))
BUILD_TIMEOUT_SECONDS = int(os.getenv("BUILD_TIMEOUT_SECONDS", "900"))

WORKER_IMAGES = {
    "python": os.getenv("WORKER_IMAGE_PYTHON", "wibes/python-builder:latest"),
    "java": os.getenv("WORKER_IMAGE_JVM", "wibes/android-jvm-builder:latest"),
    "kotlin": os.getenv("WORKER_IMAGE_JVM", "wibes/android-jvm-builder:latest"),
    "flutter": os.getenv("WORKER_IMAGE_FLUTTER", "wibes/flutter-builder:latest"),
    "javascript": os.getenv("WORKER_IMAGE_NODE", "wibes/node-capacitor-builder:latest"),
    "typescript": os.getenv("WORKER_IMAGE_NODE", "wibes/node-capacitor-builder:latest"),
    "c": os.getenv("WORKER_IMAGE_CPP", "wibes/cpp-ndk-builder:latest"),
    "cpp": os.getenv("WORKER_IMAGE_CPP", "wibes/cpp-ndk-builder:latest"),
    "csharp": os.getenv("WORKER_IMAGE_DOTNET", "wibes/dotnet-builder:latest"),
    "php": os.getenv("WORKER_IMAGE_WEB", "wibes/web-php-builder:latest"),
    "html_css": os.getenv("WORKER_IMAGE_WEB", "wibes/web-php-builder:latest"),
}


def _append_log(db, job: BuildJob, line: str, stage: str = None, progress: int = None, dep_status: str = None):
    job.logs = f"{job.logs}\n{line}".strip()
    if stage:
        job.stage = stage
    if progress is not None:
        job.progress_percent = progress
    if dep_status:
        job.dependency_status = dep_status
    db.commit()


def _build_command(language: str, output_format: str, job: BuildJob) -> list[str]:
    if language == "python":
        if output_format == "APK":
            return ["sh", "-c", "pip install --no-cache-dir -r requirements.txt 2>/dev/null || true; buildozer -v android debug && cp bin/*.apk /output/app.apk"]
        if output_format == "EXE":
            return ["sh", "-c", "pip install --no-cache-dir -r requirements.txt 2>/dev/null || true; pyinstaller --onefile --distpath /output main.py"]
        return ["sh", "-c", "zip -r /output/web_app.zip ."]
    if language in ("java", "kotlin"):
        if output_format == "JAR":
            return ["sh", "-c", "./gradlew assemble || gradle build; cp build/libs/*.jar /output/app.jar"]
        if output_format == "AAB":
            return ["sh", "-c", "chmod +x gradlew 2>/dev/null || true; ./gradlew bundleRelease && cp app/build/outputs/bundle/release/*.aab /output/app.aab"]
        return ["sh", "-c", "chmod +x gradlew 2>/dev/null || true; ./gradlew assembleDebug && cp app/build/outputs/apk/debug/*.apk /output/app.apk"]
    if language == "flutter":
        if output_format == "AAB":
            return ["sh", "-c", "flutter pub get && flutter build appbundle && cp build/app/outputs/bundle/release/app-release.aab /output/app.aab"]
        if output_format == "WEB":
            return ["sh", "-c", "flutter pub get && flutter build web && cd build/web && zip -r /output/web_app.zip ."]
        return ["sh", "-c", "flutter pub get && flutter build apk --debug && cp build/app/outputs/flutter-apk/app-debug.apk /output/app.apk"]
    if language in ("javascript", "typescript"):
        if output_format == "APK":
            return ["sh", "-c", "npm ci || npm install; npm run build || true; npx cap sync android && cd android && ./gradlew assembleDebug && cp app/build/outputs/apk/debug/*.apk /output/app.apk"]
        return ["sh", "-c", "npm ci || npm install; npm run build || true; zip -r /output/web_app.zip dist build public ."]
    if language in ("c", "cpp"):
        return ["sh", "-c", "if [ -f CMakeLists.txt ]; then mkdir -p build && cd build && cmake .. && make && cp $(find . -maxdepth 2 -type f -executable | head -n 1) /output/app.out; else clang++ -O2 main.cpp -o /output/app.out 2>/dev/null || clang -O2 main.c -o /output/app.out; fi"]
    if language == "csharp":
        if output_format == "DLL":
            return ["sh", "-c", "dotnet build -c Release -o /output"]
        return ["sh", "-c", "dotnet publish -c Release -o /output"]
    return ["sh", "-c", "zip -r /output/web_app.zip ."]


@celery_app.task(bind=True, name="app.tasks.run_isolated_build")
def run_isolated_build(self, build_id: str) -> dict:
    db = SessionLocal()
    started = time.time()
    temp_work = None
    try:
        job = db.query(BuildJob).filter(BuildJob.id == build_id).first()
        project = db.query(Project).filter(Project.id == job.project_id).first() if job else None
        if not job or not project:
            return {"status": "missing"}

        job.celery_task_id = self.request.id
        job.container_name = f"wibes_build_{build_id[:12]}"
        _append_log(db, job, f"[STAGE] Provisioning isolated Docker container ({project.language})...", "PROVISIONING_SANDBOX", 15, "Preparing sandbox")

        out_dir = ARTIFACTS_ROOT / build_id
        out_dir.mkdir(parents=True, exist_ok=True)
        temp_work = ARTIFACTS_ROOT / f"tmp_{build_id}"
        if temp_work.exists():
            shutil.rmtree(temp_work)
        shutil.copytree(project.workspace_dir, temp_work)

        image = WORKER_IMAGES.get(project.language, "wibes/python-builder:latest")
        cmd = _build_command(project.language, job.output_format, job)

        client = docker.from_env()
        _append_log(db, job, f"[INFO] Starting container {job.container_name} with image {image}", "COMPILING", 40, "Running isolated toolchain")

        container = client.containers.run(
            image=image,
            command=cmd,
            name=job.container_name,
            detach=True,
            network_disabled=True,  # Enforce --network=none during compilation
            mem_limit="2g",
            nano_cpus=2_000_000_000,
            pids_limit=256,
            volumes={
                str(temp_work.resolve()): {"bind": "/workspace", "mode": "rw"},
                str(out_dir.resolve()): {"bind": "/output", "mode": "rw"},
            },
            working_dir="/workspace",
        )

        for raw_line in container.logs(stream=True, follow=True):
            line = raw_line.decode("utf-8", errors="replace").rstrip()
            if line:
                _append_log(db, job, line)

        wait_result = container.wait(timeout=BUILD_TIMEOUT_SECONDS)
        exit_code = wait_result.get("StatusCode", 1)
        container.remove(force=True)

        if exit_code != 0:
            job.stage = "FAILED"
            job.error_message = f"Compiler container exited with non-zero code {exit_code}"
            job.completed_at = datetime.now(timezone.utc)
            job.duration_seconds = int(time.time() - started)
            db.commit()
            return {"status": "failed", "exit_code": exit_code}

        _append_log(db, job, "[STAGE] Verifying output artifact on disk...", "VERIFYING_ARTIFACT", 90, "Completed")
        artifacts = [p for p in out_dir.iterdir() if p.is_file() and p.stat().st_size > 0]
        if not artifacts:
            job.stage = "FAILED"
            job.error_message = "Build finished with exit code 0, but no output artifact file was produced."
            job.completed_at = datetime.now(timezone.utc)
            job.duration_seconds = int(time.time() - started)
            db.commit()
            return {"status": "no_artifact"}

        artifact = artifacts[0]
        job.artifact_path = str(artifact.resolve())
        job.artifact_filename = artifact.name
        job.artifact_size_bytes = artifact.stat().st_size
        job.artifact_sha256 = compute_sha256(artifact)
        job.stage = "COMPLETED"
        job.progress_percent = 100
        job.duration_seconds = int(time.time() - started)
        job.completed_at = datetime.now(timezone.utc)
        _append_log(db, job, f"[SUCCESS] Verified {artifact.name} ({job.artifact_size_bytes} bytes)")
        return {"status": "completed", "artifact": artifact.name}

    except Exception as exc:
        if job := db.query(BuildJob).filter(BuildJob.id == build_id).first():
            job.stage = "FAILED"
            job.error_message = str(exc)
            job.duration_seconds = int(time.time() - started)
            job.completed_at = datetime.now(timezone.utc)
            _append_log(db, job, f"[ERROR] {exc}")
        return {"status": "error", "detail": str(exc)}
    finally:
        if temp_work and temp_work.exists():
            shutil.rmtree(temp_work, ignore_errors=True)
        db.close()
