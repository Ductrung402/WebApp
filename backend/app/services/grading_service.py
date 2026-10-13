"""One grading transaction for REST and Socket.IO; notify only after commit."""

import asyncio
from dataclasses import dataclass
import logging
import uuid

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.models.models import CauHoi, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.schemas.exam_flow import StoredSubmissionSchema, SubmissionReceiptSchema
from app.schemas.socket import StudentRecoveryStateSchema
from app.schemas.submission import (
    AnswerItem, ExamSubmissionSchema, StoredGradeSchema,
    StudentSubmittedEventSchema, SubmissionResultResponseSchema,
)
from app.services import exam_flow_service, socket_service

logger = logging.getLogger("uvicorn.error.grading")


@dataclass
class GradingOutcome:
    result: SubmissionResultResponseSchema
    receipt: SubmissionReceiptSchema
    event: StudentSubmittedEventSchema


def grade_submission(
    db: Session, payload: ExamSubmissionSchema,
    expected_run_id: str | None = None, reason: str = "manual",
) -> GradingOutcome:
    """Lock admitted student/result/room, freeze answers, update score + state once."""
    try:
        row = db.execute(select(KetQuaThi, PhongThi, HocSinh).join(
            PhongThi, KetQuaThi.id_phong_thi == PhongThi.id_phong_thi,
        ).join(HocSinh, KetQuaThi.id_hs == HocSinh.id_hs).where(
            KetQuaThi.id_hs == payload.id_hs,
            KetQuaThi.id_phong_thi == payload.id_phong_thi,
            HocSinh.ma_code_phong_thi == PhongThi.ma_code_phong_thi,
        ).with_for_update()).one_or_none()
        if row is None:
            raise HTTPException(status_code=404, detail="Student result not found in this room.")
        result_row, room, student = row
        store = exam_flow_service.runtime_store
        if result_row.trang_thai == TrangThaiBaiLam.HOAN_THANH:
            cached = store.read_grade(room.id_phong_thi, student.id_hs)
            if not cached or cached.id_phong_thi != room.id_phong_thi or cached.result.id_hs != student.id_hs or cached.result.diem_so != result_row.diem_so:
                raise HTTPException(status_code=503, detail="Saved grading details unavailable; the recorded score is unchanged.")
            if expected_run_id and expected_run_id != cached.run_id:
                raise HTTPException(status_code=409, detail="Exam session does not match.")
            stored = store.read_submission(cached.run_id, student.id_hs)
            if not stored or stored.run_id != cached.run_id or stored.id_hs != student.id_hs:
                raise HTTPException(status_code=503, detail="Saved submission unavailable.")
            receipt = SubmissionReceiptSchema.model_validate(stored.model_dump(exclude={"answers"}))
            receipt = receipt.model_copy(update={"duplicate": True})
            return _outcome(cached.result, receipt, room, student)
        if room.trang_thai_phong not in (TrangThaiPhongThi.DANG_THI, TrangThaiPhongThi.DA_DONG):
            raise HTTPException(status_code=409, detail="Exam has not started.")
        run = exam_flow_service._read_active_run(room.ma_code_phong_thi, room.id_phong_thi)
        if expected_run_id and expected_run_id != run.run_id:
            raise HTTPException(status_code=409, detail="Exam session does not match.")
        rows = db.execute(select(CauHoi.id_cau_hoi, CauHoi.dap_an_dung).where(
            CauHoi.id_de_thi == room.id_de_thi,
        )).all()
        if not rows:
            raise HTTPException(status_code=409, detail="Exam has no questions to grade.")
        correct_answers = dict(rows)
        if any(answer not in {"A", "B", "C", "D"} for answer in correct_answers.values()):
            raise HTTPException(status_code=503, detail="Exam answer keys are invalid.")
        if set(correct_answers) != {question.id_cau_hoi for question in run.cau_hoi_list}:
            raise HTTPException(status_code=409, detail="Exam questions do not match the started session.")
        submitted = {answer.id_cau_hoi: answer.dap_an_chon for answer in payload.danh_sach_cau_tra_loi}
        if not set(submitted).issubset(correct_answers):
            raise HTTPException(status_code=422, detail="Submission contains questions from another exam.")
        stored = store.read_submission(run.run_id, student.id_hs)
        if room.trang_thai_phong == TrangThaiPhongThi.DA_DONG and stored is None:
            raise HTTPException(status_code=409, detail="Closed rooms do not accept new submissions.")
        if stored is None:
            received = exam_flow_service.now_ms()
            stored = store.receive_submission(StoredSubmissionSchema(
                submission_id=str(uuid.uuid4()), run_id=run.run_id, id_hs=student.id_hs,
                received_at=received, received_late=received > run.ends_at, reason=reason,
                answers={key: value for key, value in submitted.items() if value is not None},
            ))
        else:
            stored = stored.model_copy(update={"duplicate": True})
        if stored.id_hs != student.id_hs or stored.run_id != run.run_id or not set(stored.answers).issubset(correct_answers):
            raise HTTPException(status_code=503, detail="Saved submission does not match this exam.")
        # Always grade the first snapshot accepted by the server, including legacy receipts.
        so_cau_dung = sum(stored.answers.get(question_id) == correct for question_id, correct in correct_answers.items())
        tong_so_cau = len(correct_answers)
        diem_so = round((so_cau_dung / tong_so_cau) * 10, 2)
        response = SubmissionResultResponseSchema(
            id_hs=student.id_hs, diem_so=diem_so, so_cau_dung=so_cau_dung,
            tong_so_cau=tong_so_cau, trang_thai=TrangThaiBaiLam.HOAN_THANH,
        )
        receipt = SubmissionReceiptSchema.model_validate(stored.model_dump(exclude={"answers"}))
        outcome = _outcome(response, receipt, room, student)
        store.write_grade(StoredGradeSchema(id_phong_thi=room.id_phong_thi, run_id=run.run_id, result=response))
        previous = store.read_recovery(room.id_phong_thi, student.id_hs)
        state = StudentRecoveryStateSchema(
            id_hs=student.id_hs, ho_va_ten=student.ho_va_ten,
            ma_code_phong_thi=room.ma_code_phong_thi, id_phong_thi=room.id_phong_thi,
            trang_thai="HOAN_THANH", state_version=(previous.state_version if previous else 0) + 1,
            state_changed_at=exam_flow_service.now_ms(),
            disconnected_at=previous.disconnected_at if previous else None,
            frozen_remaining_seconds=previous.frozen_remaining_seconds if previous else None,
        )
        store.write_recovery(state)
        result_row.diem_so = diem_so
        result_row.trang_thai = TrangThaiBaiLam.HOAN_THANH
        db.flush()
        db.commit()  # One transaction for both fields; notification follows this return.
        return outcome
    except HTTPException:
        db.rollback()
        raise
    except (SQLAlchemyError, OSError, ValueError) as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Unable to save the exam score. Please retry.") from exc
    except Exception:
        db.rollback()
        raise


def _outcome(result, receipt, room, student):
    event = StudentSubmittedEventSchema(
        **result.model_dump(), id_phong_thi=room.id_phong_thi,
        ma_code_phong_thi=room.ma_code_phong_thi, ho_va_ten=student.ho_va_ten,
    )
    return GradingOutcome(result=result, receipt=receipt, event=event)


def _socket_grade(membership, token, packet):
    verified = exam_flow_service._verify_membership(membership, token)
    payload = ExamSubmissionSchema(
        id_hs=verified.id_hs, id_phong_thi=verified.id_phong_thi,
        danh_sach_cau_tra_loi=[AnswerItem(id_cau_hoi=key, dap_an_chon=value) for key, value in packet.answers.items()],
    )
    with socket_service.SessionLocal() as db:
        return grade_submission(db, payload, expected_run_id=packet.run_id, reason=packet.reason)


async def emit_student_submitted(outcome, manager):
    try:
        async with manager.lock:
            await manager.server.emit("student_submitted", outcome.event.model_dump(by_alias=True), room=manager.teacher_room(outcome.event.ma_code_phong_thi))
    except Exception:
        # The DB commit cannot be undone by a transport failure. A retry republishes the same result.
        logger.error("student_submitted_notify_failed student=%s room=%s", outcome.result.id_hs, outcome.event.ma_code_phong_thi)


async def submit_socket_and_notify(membership, token, packet, manager):
    async with manager.flow_locks.setdefault(membership.ma_code_phong_thi, asyncio.Lock()):
        outcome = await asyncio.to_thread(_socket_grade, membership, token, packet)
        await emit_student_submitted(outcome, manager)
        return outcome


def _room_code(db, room_id):
    try:
        code = db.scalar(select(PhongThi.ma_code_phong_thi).where(PhongThi.id_phong_thi == room_id))
        if code is None:
            raise HTTPException(status_code=404, detail="Room not found.")
        return code
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Room database unavailable.") from exc


async def submit_and_notify(db: Session, payload: ExamSubmissionSchema) -> SubmissionResultResponseSchema:
    # Lazy transport import avoids a service/handler import cycle.
    from app.core.socket_manager import socket_manager
    code = await asyncio.to_thread(_room_code, db, payload.id_phong_thi)
    async with socket_manager.flow_locks.setdefault(code, asyncio.Lock()):
        outcome = await asyncio.to_thread(grade_submission, db, payload)
        await emit_student_submitted(outcome, socket_manager)
        return outcome.result


def get_student_result(db, payload):
    """Read only the admitted student's committed result, including closed rooms."""
    try:
        row = db.execute(select(KetQuaThi.trang_thai, KetQuaThi.diem_so).join(
            PhongThi, KetQuaThi.id_phong_thi == PhongThi.id_phong_thi,
        ).join(HocSinh, KetQuaThi.id_hs == HocSinh.id_hs).where(
            KetQuaThi.id_hs == payload.id_hs, KetQuaThi.id_phong_thi == payload.id_phong_thi,
            HocSinh.ma_code_phong_thi == PhongThi.ma_code_phong_thi,
        )).one_or_none()
        if row is None:
            raise HTTPException(status_code=404, detail="Student result not found in this room.")
        if row.trang_thai != TrangThaiBaiLam.HOAN_THANH:
            raise HTTPException(status_code=409, detail="The submission has not been graded yet.")
        cached = exam_flow_service.runtime_store.read_grade(payload.id_phong_thi, payload.id_hs)
        if not cached or cached.id_phong_thi != payload.id_phong_thi or cached.result.id_hs != payload.id_hs or cached.result.diem_so != row.diem_so:
            raise HTTPException(status_code=503, detail="Saved grading details unavailable.")
        return cached.result
    except (SQLAlchemyError, OSError, ValueError) as exc:
        raise HTTPException(status_code=503, detail="Saved grading details unavailable.") from exc
