"""Password hashing and JWT authentication for teachers."""

from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from passlib.context import CryptContext
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.config import get_auth_settings
from app.core.database import get_db
from app.models.models import GiaoVien

password_context = CryptContext(schemes=["bcrypt"], deprecated="auto", bcrypt__truncate_error=True)
bearer_scheme = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    if len(password.encode("utf-8")) > 72 or "\x00" in password:
        raise ValueError("Password must not exceed 72 UTF-8 bytes or contain null bytes.")
    return password_context.hash(password)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    if len(plain_password.encode("utf-8")) > 72 or "\x00" in plain_password:
        return False
    try:
        return password_context.verify(plain_password, hashed_password)
    except (ValueError, TypeError):
        return False


def create_access_token(data: dict, expires_delta: Optional[timedelta] = None) -> str:
    settings = get_auth_settings()
    now = datetime.now(timezone.utc)
    duration = expires_delta if expires_delta is not None else timedelta(
        minutes=settings.access_token_expire_minutes
    )
    claims = data.copy()
    claims.update({"exp": now + duration, "iat": now})
    return jwt.encode(claims, settings.secret_key, algorithm=settings.algorithm)


def credentials_exception() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Invalid or expired credentials.",
        headers={"WWW-Authenticate": "Bearer"},
    )


def get_bearer_token(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(bearer_scheme),
) -> str:
    if credentials is None:
        raise credentials_exception()
    return credentials.credentials


def get_current_teacher(
    token: str = Depends(get_bearer_token), db: Session = Depends(get_db),
) -> GiaoVien:
    settings = get_auth_settings()
    try:
        claims = jwt.decode(
            token, settings.secret_key, algorithms=[settings.algorithm],
            options={"require_exp": True, "require_sub": True},
        )
        teacher_id = claims.get("sub")
        if not isinstance(teacher_id, str) or not teacher_id.strip():
            raise credentials_exception()
    except (JWTError, ValueError, TypeError) as exc:
        raise credentials_exception() from exc
    try:
        teacher = db.get(GiaoVien, teacher_id)
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Authentication database unavailable.") from exc
    if teacher is None:
        raise credentials_exception()
    return teacher
