"""Validate room creation before the authentication dependency performs SQL."""

from fastapi import Depends
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import get_bearer_token, get_current_teacher
from app.models.models import GiaoVien
from app.schemas.room import RoomCreateSchema


def get_room_teacher(
    payload: RoomCreateSchema,
    token: str = Depends(get_bearer_token), db: Session = Depends(get_db),
) -> GiaoVien:
    return get_current_teacher(token, db)
