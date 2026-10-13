"""JWT settings loaded from the environment or backend/.env."""

from dataclasses import dataclass, field
from functools import lru_cache
import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parents[2] / ".env")


@dataclass(frozen=True)
class AuthSettings:
    secret_key: str = field(repr=False)
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30


@lru_cache
def get_auth_settings() -> AuthSettings:
    secret_key = os.getenv("JWT_SECRET_KEY", "")
    if len(secret_key.encode("utf-8")) < 32:
        raise RuntimeError("Set JWT_SECRET_KEY to a private key of at least 32 bytes.")
    try:
        expire_minutes = int(os.getenv("ACCESS_TOKEN_EXPIRE_MINUTES", "30"))
    except ValueError as exc:
        raise RuntimeError("ACCESS_TOKEN_EXPIRE_MINUTES must be a positive integer.") from exc
    if expire_minutes <= 0:
        raise RuntimeError("ACCESS_TOKEN_EXPIRE_MINUTES must be a positive integer.")
    return AuthSettings(secret_key=secret_key, access_token_expire_minutes=expire_minutes)
