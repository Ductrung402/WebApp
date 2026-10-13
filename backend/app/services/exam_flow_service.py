"""Transactional exam start, deadline recovery and legacy submission receipts."""

import time
import uuid

from fastapi import HTTPException
from pydantic import ValidationError
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError

from app.core.exam_runtime import runtime_store
from app.core.security import get_current_teacher
from app.models.models import CauHoi, DeThi, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.schemas.exam_flow import (
    ExamStartedSchema, ExamSubmitSchema, ExamSyncSchema, StoredSubmissionSchema,
    StudentQuestionSchema, SubmissionReceiptSchema,
)
from app.schemas.socket import SocketJoinSchema, SocketMembershipSchema
from app.services import socket_service


def now_ms():
    return time.time_ns() // 1_000_000


def _read_active_run(code, room_id):
    run = runtime_store.read_run(code)
    if run is None or run.id_phong_thi != room_id or run.ma_code_phong_thi != code:
        raise HTTPException(status_code=503, detail="Exam timing is unavailable. Contact the teacher.")
    return run.model_copy(update={"server_time": now_ms()})


def start_exam(code: str, token: str | None) -> ExamStartedSchema:
    with socket_service.SessionLocal() as db:
        try:
            if not token:
                raise HTTPException(status_code=401, detail="Teacher authentication required.")
            teacher = get_current_teacher(token, db)
            room = db.scalar(select(PhongThi).where(
                PhongThi.ma_code_phong_thi == code, PhongThi.id_gv == teacher.id_gv,
            ).with_for_update())
            if room is None:
                raise HTTPException(status_code=403, detail="Teacher does not own this room.")
            if room.trang_thai_phong == TrangThaiPhongThi.DANG_THI:
                return _read_active_run(code, room.id_phong_thi)  # Retry never resets the deadline.
            if room.trang_thai_phong != TrangThaiPhongThi.DANG_CHO:
                raise HTTPException(status_code=409, detail="Room cannot be started.")
            title = db.scalar(select(DeThi.ten_de_thi).where(DeThi.id_de_thi == room.id_de_thi))
            # Positive whitelist: dap_an_dung is never even selected for transmission.
            rows = db.execute(select(
                CauHoi.id_cau_hoi, CauHoi.noi_dung,
                CauHoi.dap_an_a, CauHoi.dap_an_b, CauHoi.dap_an_c, CauHoi.dap_an_d,
            ).where(CauHoi.id_de_thi == room.id_de_thi).order_by(CauHoi.id_cau_hoi)).all()
            if not rows:
                raise HTTPException(status_code=409, detail="Exam has no questions.")
            started = now_ms()
            run = ExamStartedSchema(
                run_id=str(uuid.uuid4()), id_phong_thi=room.id_phong_thi, id_de_thi=room.id_de_thi,
                ma_code_phong_thi=code, ten_de_thi=title, ten_lop=room.ten_lop,
                thoi_gian_lam_bai=room.thoi_gian_lam_bai, starts_at=started,
                ends_at=started + room.thoi_gian_lam_bai * 60000, server_time=started,
                cau_hoi_list=[StudentQuestionSchema.model_validate(row._mapping) for row in rows],
            )
            # Persist timing before commit. A prepared file is only served when DB is DANG_THI.
            # If commit fails, DB rollback leaves DANG_CHO and a retry replaces this file.
            runtime_store.write_run(run)
            room.trang_thai_phong = TrangThaiPhongThi.DANG_THI
            db.flush()
            db.commit()  # Socket broadcast happens only after this succeeds.
            return run.model_copy(update={"server_time": now_ms()})
        except HTTPException:
            db.rollback()
            raise
        except ValidationError as exc:
            db.rollback()
            raise HTTPException(status_code=400, detail="Exam question data is invalid.") from exc
        except (SQLAlchemyError, OSError, ValueError) as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="Unable to start the exam. Please retry.") from exc
        except Exception:
            db.rollback()
            raise


def _verify_membership(membership, token):
    payload = SocketJoinSchema.model_validate(membership.model_dump(include={"ma_code_phong_thi", "user_type", "id_hs"}))
    return socket_service.authorize_socket_join(payload, token)


def _room_state(verified):
    with socket_service.SessionLocal() as db:
        return db.scalar(select(PhongThi.trang_thai_phong).where(PhongThi.id_phong_thi == verified.id_phong_thi))


def sync_exam(membership: SocketMembershipSchema, token: str | None) -> ExamSyncSchema:
    verified = _verify_membership(membership, token)
    try:
        state = _room_state(verified)
        if state != TrangThaiPhongThi.DANG_THI:
            return ExamSyncSchema(server_time=now_ms())
        run = _read_active_run(verified.ma_code_phong_thi, verified.id_phong_thi)
        stored = runtime_store.read_submission(run.run_id, verified.id_hs) if verified.user_type == "student" else None
        with socket_service.SessionLocal() as db:
            completed = verified.user_type == "student" and db.scalar(select(KetQuaThi.trang_thai).where(
                KetQuaThi.id_hs == verified.id_hs, KetQuaThi.id_phong_thi == verified.id_phong_thi,
            )) == TrangThaiBaiLam.HOAN_THANH
        receipt = SubmissionReceiptSchema.model_validate(stored.model_dump(exclude={"answers"})) if stored and completed else None
        return ExamSyncSchema(server_time=now_ms(), exam=run, submission=receipt)
    except (SQLAlchemyError, OSError, ValueError) as exc:
        raise HTTPException(status_code=503, detail="Exam synchronization unavailable.") from exc


def submit_exam(membership: SocketMembershipSchema, token: str | None, payload: ExamSubmitSchema) -> SubmissionReceiptSchema:
    verified = _verify_membership(membership, token)
    if verified.user_type != "student":
        raise HTTPException(status_code=403, detail="Only students can submit answers.")
    try:
        if _room_state(verified) != TrangThaiPhongThi.DANG_THI:
            raise HTTPException(status_code=409, detail="Exam has not started.")
        run = _read_active_run(verified.ma_code_phong_thi, verified.id_phong_thi)
        if payload.run_id != run.run_id:
            raise HTTPException(status_code=409, detail="Exam session does not match.")
        allowed = {question.id_cau_hoi for question in run.cau_hoi_list}
        if not set(payload.answers).issubset(allowed):
            raise HTTPException(status_code=422, detail="Answers contain unknown question IDs.")
        received = now_ms()
        stored = runtime_store.receive_submission(StoredSubmissionSchema(
            submission_id=str(uuid.uuid4()), run_id=run.run_id, id_hs=verified.id_hs,
            received_at=received, received_late=received > run.ends_at,
            reason=payload.reason, answers=payload.answers,
        ))
        # No answer key, grade or KetQuaThi state update: Task 5.1 will process receipts.
        return SubmissionReceiptSchema.model_validate(stored.model_dump(exclude={"answers"}))
    except (SQLAlchemyError, OSError, ValueError) as exc:
        raise HTTPException(status_code=503, detail="Unable to receive the submission. Please retry.") from exc
