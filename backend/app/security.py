import hashlib
import os
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy.orm import Session

from .models import SessionLocal, User

SECRET_KEY = os.getenv("JWT_SECRET_KEY", "change-this-secret-key-in-production")
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 60 * 24
MAX_UPLOAD_BYTES = int(os.getenv("MAX_UPLOAD_BYTES", str(100 * 1024 * 1024)))
MAX_EXTRACTED_BYTES = MAX_UPLOAD_BYTES * 2
MAX_ZIP_ENTRIES = 10000

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="/api/auth/token")


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def hash_password(password: str) -> str:
    return pwd_context.hash(password)


def verify_password(plain_password: str, password_hash: str) -> bool:
    return pwd_context.verify(plain_password, password_hash)


def create_access_token(username: str, expires_delta: Optional[timedelta] = None) -> str:
    expire = datetime.now(timezone.utc) + (expires_delta or timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES))
    payload = {"sub": username, "exp": expire}
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)


def get_current_user(
    token: str = Depends(oauth2_scheme),
    db: Session = Depends(get_db)
) -> User:
    credentials_exc = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid or expired authentication token",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
        username: Optional[str] = payload.get("sub")
        if not username:
            raise credentials_exc
    except JWTError as exc:
        raise credentials_exc from exc

    user = db.query(User).filter(User.username == username).first()
    if user is None:
        raise credentials_exc
    return user


def safe_extract_zip(zip_path: Path, extract_dir: Path) -> int:
    """Safely extracts untrusted ZIP archives with ZIP Slip, symlink, and bomb guards."""
    extract_dir.mkdir(parents=True, exist_ok=True)
    real_base = extract_dir.resolve()
    total_uncompressed = 0
    file_count = 0

    with zipfile.ZipFile(zip_path, "r") as zf:
        infos = zf.infolist()
        if len(infos) > MAX_ZIP_ENTRIES:
            raise ValueError(f"ZIP exceeds max entry count ({MAX_ZIP_ENTRIES})")

        for info in infos:
            # Reject symlinks (external_attr Unix symlink mode 0o120000)
            unix_mode = (info.external_attr >> 16) & 0o170000
            if unix_mode == 0o120000:
                raise ValueError(f"Blocked symlink in ZIP archive: {info.filename}")

            if info.filename.startswith("/") or ".." in Path(info.filename).parts:
                raise ValueError(f"Blocked path traversal attempt: {info.filename}")

            target = (real_base / info.filename).resolve()
            if target != real_base and real_base not in target.parents:
                raise ValueError(f"ZIP Slip escape detected: {info.filename}")

            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                file_count += 1
                total_uncompressed += info.file_size
                if total_uncompressed > MAX_EXTRACTED_BYTES:
                    raise ValueError("ZIP decompression bomb guard triggered")
                target.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info, "r") as src, open(target, "wb") as dst:
                    while chunk := src.read(65536):
                        dst.write(chunk)
    return file_count


def compute_sha256(file_path: Path) -> str:
    h = hashlib.sha256()
    with open(file_path, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()
