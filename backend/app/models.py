import os
from datetime import datetime, timezone
from sqlalchemy import Boolean, Column, DateTime, ForeignKey, Integer, BigInteger, String, Text, create_engine
from sqlalchemy.orm import declarative_base, relationship, sessionmaker

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql+psycopg2://wibes:wibes_secret@postgres:5432/wibes_compiler"
)

engine = create_engine(DATABASE_URL, pool_pre_ping=True)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String(64), unique=True, nullable=False, index=True)
    password_hash = Column(String(256), nullable=False)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    projects = relationship("Project", back_populates="owner", cascade="all, delete-orphan")


class Project(Base):
    __tablename__ = "projects"

    id = Column(String(64), primary_key=True, index=True)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    name = Column(String(128), nullable=False)
    workspace_dir = Column(String(512), nullable=False)
    archive_path = Column(String(512), nullable=False)
    size_bytes = Column(BigInteger, nullable=False)
    sha256 = Column(String(64), nullable=False)
    file_count = Column(Integer, default=0)
    language = Column(String(32), default="python")
    framework = Column(String(64), default="Unknown")
    entry_point = Column(String(256), nullable=True)
    security_passed = Column(Boolean, default=True)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))

    owner = relationship("User", back_populates="projects")
    builds = relationship("BuildJob", back_populates="project", cascade="all, delete-orphan")


class BuildJob(Base):
    __tablename__ = "build_jobs"

    id = Column(String(64), primary_key=True, index=True)
    project_id = Column(String(64), ForeignKey("projects.id"), nullable=False)
    owner_id = Column(Integer, ForeignKey("users.id"), nullable=False)
    app_name = Column(String(128), nullable=False)
    package_name = Column(String(128), nullable=False)
    version_name = Column(String(32), default="1.0.0")
    version_code = Column(Integer, default=1)
    min_sdk = Column(Integer, default=26)
    target_sdk = Column(Integer, default=35)
    output_format = Column(String(32), nullable=False)
    build_mode = Column(String(16), default="debug")
    stage = Column(String(32), default="QUEUED")
    progress_percent = Column(Integer, default=0)
    dependency_status = Column(String(128), default="Queued")
    logs = Column(Text, default="")
    error_message = Column(Text, nullable=True)
    artifact_path = Column(String(512), nullable=True)
    artifact_filename = Column(String(256), nullable=True)
    artifact_size_bytes = Column(BigInteger, default=0)
    artifact_sha256 = Column(String(64), nullable=True)
    celery_task_id = Column(String(128), nullable=True)
    container_name = Column(String(128), nullable=True)
    duration_seconds = Column(Integer, default=0)
    created_at = Column(DateTime, default=lambda: datetime.now(timezone.utc))
    completed_at = Column(DateTime, nullable=True)

    project = relationship("Project", back_populates="builds")


def init_db() -> None:
    Base.metadata.create_all(bind=engine)
