"""Teacher-only exam routes; all business logic lives in exam_service."""

from typing import List

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.exam_dependencies import get_ai_exam_teacher, get_manual_exam_teacher
from app.core.security import get_current_teacher
from app.models.models import GiaoVien
from app.schemas.exam import (
    DeThiCreateManualSchema, DeThiDetailResponseSchema, DeThiGenerateAISchema,
    DeThiListResponseSchema, ExamDeleteResponseSchema,
)
from app.services import exam_service

router = APIRouter(prefix="/exams", tags=["Teacher exams"])


@router.post("/preview-ai", response_model=DeThiCreateManualSchema)
def preview_ai(
    payload: DeThiGenerateAISchema, teacher: GiaoVien = Depends(get_ai_exam_teacher),
    db: Session = Depends(get_db),
) -> DeThiCreateManualSchema:
    return exam_service.preview_ai_exam(db, payload)


@router.post("/generate-ai", response_model=DeThiDetailResponseSchema, status_code=status.HTTP_201_CREATED)
def generate_ai(
    payload: DeThiGenerateAISchema, teacher: GiaoVien = Depends(get_ai_exam_teacher),
    db: Session = Depends(get_db),
) -> DeThiDetailResponseSchema:
    return exam_service.generate_ai_exam(db, teacher.id_gv, payload)


@router.post("", response_model=DeThiDetailResponseSchema, status_code=status.HTTP_201_CREATED)
def create_manual(
    payload: DeThiCreateManualSchema, teacher: GiaoVien = Depends(get_manual_exam_teacher),
    db: Session = Depends(get_db),
) -> DeThiDetailResponseSchema:
    return exam_service.create_manual_exam(db, teacher.id_gv, payload)


@router.get("", response_model=List[DeThiListResponseSchema])
def list_owned(teacher: GiaoVien = Depends(get_current_teacher), db: Session = Depends(get_db)):
    return exam_service.list_exams(db, teacher.id_gv)


@router.get("/{id_de_thi}", response_model=DeThiDetailResponseSchema)
def get_detail(
    id_de_thi: str, teacher: GiaoVien = Depends(get_current_teacher), db: Session = Depends(get_db),
) -> DeThiDetailResponseSchema:
    return exam_service.get_exam(db, teacher.id_gv, id_de_thi)


@router.put("/{id_de_thi}", response_model=DeThiDetailResponseSchema)
def update(
    id_de_thi: str, payload: DeThiCreateManualSchema,
    teacher: GiaoVien = Depends(get_manual_exam_teacher), db: Session = Depends(get_db),
) -> DeThiDetailResponseSchema:
    return exam_service.update_exam(db, teacher.id_gv, id_de_thi, payload)


@router.delete("/{id_de_thi}", response_model=ExamDeleteResponseSchema)
def delete(
    id_de_thi: str, teacher: GiaoVien = Depends(get_current_teacher), db: Session = Depends(get_db),
) -> ExamDeleteResponseSchema:
    return exam_service.delete_exam(db, teacher.id_gv, id_de_thi)
