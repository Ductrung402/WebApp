"""Owned exam CRUD and atomic exam/question writes."""

from contextlib import contextmanager
from typing import List

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from app.models.models import CauHoi, DeThi, PhongThi
from app.schemas.exam import (
    CauHoiCreateSchema, DeThiCreateManualSchema, DeThiDetailResponseSchema,
    DeThiGenerateAISchema, DeThiListResponseSchema, ExamDeleteResponseSchema,
)
from app.services import gemini_service


@contextmanager
def _write_transaction(db: Session):
    """Reuse the auth dependency's transaction; never commit an intermediate flush."""
    try:
        yield
        db.commit()
    except gemini_service.GeminiServiceError as exc:
        db.rollback()
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=409, detail="Exam data conflicts with existing records.") from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Exam database unavailable.") from exc
    except Exception:
        db.rollback()
        raise


def _owned_exam(db: Session, teacher_id: str, exam_id: str, lock: bool = False) -> DeThi:
    query = select(DeThi).where(
        DeThi.id_de_thi == exam_id, DeThi.id_gv == teacher_id,
    ).options(selectinload(DeThi.cau_hoi_list))
    if lock:
        query = query.with_for_update()
    exam = db.scalar(query)
    if exam is None:
        raise HTTPException(status_code=404, detail="Exam not found.")
    return exam


def _ensure_not_used(db: Session, exam_id: str):
    if db.scalar(select(PhongThi.id_phong_thi).where(PhongThi.id_de_thi == exam_id).limit(1)):
        raise HTTPException(status_code=409, detail="An exam referenced by an exam room cannot be changed or deleted.")


def _insert_exam(
    db: Session, teacher_id: str, title: str, grade: str | None,
    document_type: str, source: str | None, questions: List[CauHoiCreateSchema],
) -> DeThiDetailResponseSchema:
    exam = DeThi(
        id_gv=teacher_id, ten_de_thi=title, khoi=grade,
        so_luong_cau_hoi=len(questions), kieu_tai_lieu=document_type,
        noi_dung_tai_lieu=source,
        cau_hoi_list=[CauHoi(**question.model_dump()) for question in questions],
    )
    db.add(exam)
    db.flush()  # Inserts parent + all children; the transaction remains uncommitted.
    return DeThiDetailResponseSchema.model_validate(exam)


def create_manual_exam(
    db: Session, teacher_id: str, payload: DeThiCreateManualSchema,
) -> DeThiDetailResponseSchema:
    with _write_transaction(db):
        response = _insert_exam(
            db, teacher_id, payload.ten_de_thi, payload.khoi,
            payload.kieu_tai_lieu, payload.noi_dung_tai_lieu, payload.cau_hoi_list,
        )
    return response


def _generate_questions(payload: DeThiGenerateAISchema) -> List[CauHoiCreateSchema]:
    generated = gemini_service.generate_quiz_from_text(payload.raw_text, payload.num_questions)
    try:
        questions = [CauHoiCreateSchema.model_validate(question) for question in generated]
    except (ValidationError, TypeError) as exc:
        raise gemini_service.GeminiResponseError("Gemini returned invalid question data.") from exc
    if len(questions) != payload.num_questions:
        raise gemini_service.GeminiResponseError("Gemini returned an incorrect number of questions.")
    return questions


def preview_ai_exam(db: Session, payload: DeThiGenerateAISchema) -> DeThiCreateManualSchema:
    """Return a validated draft without adding ORM objects or committing."""
    try:
        return DeThiCreateManualSchema(
            ten_de_thi=payload.ten_de_thi, khoi=payload.khoi, kieu_tai_lieu="TEXT",
            noi_dung_tai_lieu=payload.raw_text, cau_hoi_list=_generate_questions(payload),
        )
    except gemini_service.GeminiServiceError as exc:
        db.rollback()
        raise HTTPException(status_code=exc.status_code, detail=str(exc)) from exc


def generate_ai_exam(
    db: Session, teacher_id: str, payload: DeThiGenerateAISchema,
) -> DeThiDetailResponseSchema:
    with _write_transaction(db):
        questions = _generate_questions(payload)
        response = _insert_exam(
            db, teacher_id, payload.ten_de_thi, payload.khoi,
            "TEXT", payload.raw_text, questions,
        )
    return response


def list_exams(db: Session, teacher_id: str) -> List[DeThiListResponseSchema]:
    try:
        rows = db.execute(select(
            DeThi.id_de_thi, DeThi.id_gv, DeThi.ten_de_thi, DeThi.khoi,
            DeThi.so_luong_cau_hoi, DeThi.kieu_tai_lieu, DeThi.ngay_tao,
        ).where(DeThi.id_gv == teacher_id).order_by(
            DeThi.ngay_tao.desc().nulls_last(), DeThi.id_de_thi,
        )).all()
        return [DeThiListResponseSchema.model_validate(row._mapping) for row in rows]
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Exam database unavailable.") from exc


def get_exam(db: Session, teacher_id: str, exam_id: str) -> DeThiDetailResponseSchema:
    try:
        return DeThiDetailResponseSchema.model_validate(_owned_exam(db, teacher_id, exam_id))
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Exam database unavailable.") from exc


def update_exam(
    db: Session, teacher_id: str, exam_id: str, payload: DeThiCreateManualSchema,
) -> DeThiDetailResponseSchema:
    with _write_transaction(db):
        exam = _owned_exam(db, teacher_id, exam_id, lock=True)
        _ensure_not_used(db, exam_id)
        exam.ten_de_thi = payload.ten_de_thi
        exam.khoi = payload.khoi
        exam.kieu_tai_lieu = payload.kieu_tai_lieu
        exam.noi_dung_tai_lieu = payload.noi_dung_tai_lieu
        exam.cau_hoi_list = [CauHoi(**question.model_dump()) for question in payload.cau_hoi_list]
        exam.so_luong_cau_hoi = len(exam.cau_hoi_list)
        db.flush()  # delete-orphan removes replaced questions in this same transaction.
        response = DeThiDetailResponseSchema.model_validate(exam)
    return response


def delete_exam(db: Session, teacher_id: str, exam_id: str) -> ExamDeleteResponseSchema:
    with _write_transaction(db):
        exam = _owned_exam(db, teacher_id, exam_id, lock=True)
        _ensure_not_used(db, exam_id)
        response = ExamDeleteResponseSchema(id_de_thi=exam.id_de_thi)
        db.delete(exam)  # Use ORM delete so the existing question cascade runs.
        db.flush()
    return response
