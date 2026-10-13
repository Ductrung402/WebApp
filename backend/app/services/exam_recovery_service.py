"""Scoped worker-thread transactions for ERR_02 and deadline-bound recovery."""

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError

from app.models.models import HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.schemas.exam_recovery import ReconnectExamResponseSchema
from app.schemas.socket import StudentRecoveryStateSchema
from app.services import exam_flow_service, socket_service


def _record(room, student, result):
    stored = exam_flow_service.runtime_store.read_recovery(room.id_phong_thi, student.id_hs)
    # A prepared sidecar from a failed DB commit must never overwrite the DB state.
    if stored and stored.trang_thai == result.trang_thai.value:
        return stored
    return StudentRecoveryStateSchema(
        id_hs=student.id_hs, ho_va_ten=student.ho_va_ten,
        ma_code_phong_thi=room.ma_code_phong_thi, id_phong_thi=room.id_phong_thi,
        trang_thai=result.trang_thai.value,
        state_version=stored.state_version if stored else 0,
        disconnected_at=stored.disconnected_at if stored else None,
        frozen_remaining_seconds=stored.frozen_remaining_seconds if stored else None,
    )


def _target(db, membership, lock=True):
    query = select(KetQuaThi, PhongThi, HocSinh).join(
        PhongThi, KetQuaThi.id_phong_thi == PhongThi.id_phong_thi,
    ).join(HocSinh, KetQuaThi.id_hs == HocSinh.id_hs).where(
        KetQuaThi.id_hs == membership.id_hs,
        KetQuaThi.id_phong_thi == membership.id_phong_thi,
        PhongThi.ma_code_phong_thi == membership.ma_code_phong_thi,
        HocSinh.ma_code_phong_thi == membership.ma_code_phong_thi,
    )
    return db.execute(query.with_for_update() if lock else query).one_or_none()


def mark_disconnected(membership, disconnected_at=None):
    with socket_service.SessionLocal() as db:
        try:
            row = _target(db, membership)
            if row is None:
                return None  # Admission may have been cascade-deleted.
            result, room, student = row
            if result.trang_thai == TrangThaiBaiLam.HOAN_THANH or room.trang_thai_phong == TrangThaiPhongThi.DA_DONG:
                return None
            current = _record(room, student, result)
            if result.trang_thai == TrangThaiBaiLam.MAT_KET_NOI:
                return current  # Duplicate disconnect cannot replace the original freeze time.
            now = disconnected_at if disconnected_at is not None else exam_flow_service.now_ms()
            remaining = None
            if room.trang_thai_phong == TrangThaiPhongThi.DANG_THI:
                try:
                    run = exam_flow_service._read_active_run(room.ma_code_phong_thi, room.id_phong_thi)
                    remaining = max(0, (run.ends_at - now + 999) // 1000)
                except HTTPException as exc:
                    if exc.status_code != 503:
                        raise
                    # Missing timing must not prevent the mandatory DB disconnect state.
                    # Recovery still fails closed until the original timing is available.
            state = current.model_copy(update={
                "trang_thai": "MAT_KET_NOI", "state_version": current.state_version + 1,
                "state_changed_at": now, "disconnected_at": now,
                "frozen_remaining_seconds": remaining,
            })
            exam_flow_service.runtime_store.write_recovery(state)
            result.trang_thai = TrangThaiBaiLam.MAT_KET_NOI
            db.flush()
            db.commit()  # Only committed state is broadcast by the Socket handler.
            return state
        except (SQLAlchemyError, OSError, ValueError) as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="Unable to persist disconnected student state.") from exc
        except Exception:
            db.rollback()
            raise


def reconnect_exam(membership, token):
    verified = exam_flow_service._verify_membership(membership, token)
    with socket_service.SessionLocal() as db:
        try:
            row = _target(db, verified)
            if row is None:
                raise HTTPException(status_code=404, detail="Student result not found.")
            result, room, student = row
            remaining = None
            expired = False
            if room.trang_thai_phong == TrangThaiPhongThi.DANG_THI:
                run = exam_flow_service._read_active_run(room.ma_code_phong_thi, room.id_phong_thi)
                remaining = max(0, (run.ends_at - exam_flow_service.now_ms() + 999) // 1000)
                expired = remaining == 0
            elif room.trang_thai_phong != TrangThaiPhongThi.DANG_CHO:
                raise HTTPException(status_code=409, detail="Room is closed or unavailable.")
            current = _record(room, student, result)
            if result.trang_thai == TrangThaiBaiLam.MAT_KET_NOI and not expired:
                current = current.model_copy(update={
                    "trang_thai": "DANG_THI", "state_version": current.state_version + 1,
                    "state_changed_at": exam_flow_service.now_ms(),
                })
                exam_flow_service.runtime_store.write_recovery(current)
                result.trang_thai = TrangThaiBaiLam.DANG_THI
                db.flush()
                db.commit()
            # Completed/expired work is never resurrected or given a new deadline.
        except (SQLAlchemyError, OSError, ValueError) as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="Unable to recover the exam state.") from exc
        except Exception:
            db.rollback()
            raise
    snapshot = exam_flow_service.sync_exam(verified, token)
    return ReconnectExamResponseSchema(
        **snapshot.model_dump(exclude={"student_state"}), student_state=current,
        remaining_seconds=remaining, exam_expired=expired,
    )


def read_student_states(membership):
    """Snapshot for a verified teacher; WHERE scopes every query to one room."""
    with socket_service.SessionLocal() as db:
        try:
            rows = db.execute(select(KetQuaThi, PhongThi, HocSinh).join(
                PhongThi, KetQuaThi.id_phong_thi == PhongThi.id_phong_thi,
            ).join(HocSinh, KetQuaThi.id_hs == HocSinh.id_hs).where(
                PhongThi.id_phong_thi == membership.id_phong_thi,
                PhongThi.ma_code_phong_thi == membership.ma_code_phong_thi,
                HocSinh.ma_code_phong_thi == membership.ma_code_phong_thi,
            )).all()
            return [_record(room, student, result) for result, room, student in rows]
        except (SQLAlchemyError, OSError, ValueError) as exc:
            raise HTTPException(status_code=503, detail="Student states unavailable.") from exc


def read_student_state(membership):
    with socket_service.SessionLocal() as db:
        try:
            row = _target(db, membership, lock=False)
            if row is None:
                raise HTTPException(status_code=404, detail="Student result not found.")
            result, room, student = row
            return _record(room, student, result)
        except (SQLAlchemyError, OSError, ValueError) as exc:
            raise HTTPException(status_code=503, detail="Student state unavailable.") from exc
