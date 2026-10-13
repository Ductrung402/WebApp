"""Validate write payloads before the teacher dependency queries the database."""

from fastapi import Depends
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import get_bearer_token, get_current_teacher
from app.models.models import GiaoVien
from app.schemas.exam import DeThiCreateManualSchema, DeThiGenerateAISchema


def get_manual_exam_teacher(
    payload: DeThiCreateManualSchema,
    token: str = Depends(get_bearer_token), db: Session = Depends(get_db),
) -> GiaoVien:
    return get_current_teacher(token, db)


def get_ai_exam_teacher(
    payload: DeThiGenerateAISchema,
    token: str = Depends(get_bearer_token), db: Session = Depends(get_db),
) -> GiaoVien:
    return get_current_teacher(token, db)
