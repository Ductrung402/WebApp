"""Owned room creation and anonymous admission; no exam lifecycle/Socket.IO logic."""

import secrets
import string

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, selectinload

from app.models.models import (
    DeThi, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi,
)
from app.schemas.room import (
    RoomCreateSchema, RoomDetailResponseSchema, RoomResponseSchema,
    StudentJoinResponseSchema, StudentJoinSchema,
    StudentResponseSchema, StudentScoreSchema,
)

CODE_ALPHABET = string.ascii_uppercase + string.digits
MAX_CODE_ATTEMPTS = 20
MAX_INSERT_ATTEMPTS = 5


def generate_room_code(db: Session) -> str:
    """Pre-check candidates; the existing DB UNIQUE constraint handles races."""
    for _ in range(MAX_CODE_ATTEMPTS):
        code = "".join(secrets.choice(CODE_ALPHABET) for _ in range(6))
        existing = db.scalar(select(PhongThi.id_phong_thi).where(
            PhongThi.ma_code_phong_thi == code,
        ).limit(1))
        if existing is None:
            return code
    raise HTTPException(status_code=503, detail="Unable to allocate a unique room code. Please retry.")


def _is_code_collision(exc: IntegrityError) -> bool:
    """Retry only this UNIQUE violation, never unrelated integrity errors."""
    original = exc.orig
    sqlstate = getattr(original, "pgcode", None) or getattr(original, "sqlstate", None)
    constraint = getattr(getattr(original, "diag", None), "constraint_name", "") or ""
    if sqlstate == "23505":
        return constraint == "phong_thi_ma_code_phong_thi_key"
    return "UNIQUE constraint failed: phong_thi.ma_code_phong_thi" in str(original)


def create_room(db: Session, teacher_id: str, payload: RoomCreateSchema) -> RoomResponseSchema:
    for _ in range(MAX_INSERT_ATTEMPTS):
        try:
            # Coordinate with exam update/delete, which lock the same parent row.
            exam = db.scalar(select(DeThi).where(
                DeThi.id_de_thi == payload.id_de_thi, DeThi.id_gv == teacher_id,
            ).with_for_update())
            if exam is None:
                raise HTTPException(status_code=404, detail="Exam not found.")
            room = PhongThi(
                id_gv=teacher_id, id_de_thi=exam.id_de_thi,
                ma_code_phong_thi=generate_room_code(db), ten_lop=payload.ten_lop,
                thoi_gian_lam_bai=payload.thoi_gian_lam_bai,
                trang_thai_phong=TrangThaiPhongThi.DANG_CHO,
            )
            db.add(room)
            db.flush()
            response = RoomResponseSchema.model_validate(room)
            db.commit()
            return response
        except IntegrityError as exc:
            db.rollback()
            if _is_code_collision(exc):
                # Restart the entire transaction and reacquire the exam lock.
                continue
            raise HTTPException(status_code=409, detail="Room data conflicts with existing records.") from exc
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="Room database unavailable.") from exc
        except Exception:
            db.rollback()
            raise
    raise HTTPException(status_code=503, detail="Unable to allocate a unique room code. Please retry.")


def get_room(db: Session, teacher_id: str, room_id: str) -> RoomDetailResponseSchema:
    try:
        room = db.scalar(select(PhongThi).where(
            PhongThi.id_phong_thi == room_id, PhongThi.id_gv == teacher_id,
        ).options(selectinload(PhongThi.hoc_sinh_list)))
        if room is None:
            raise HTTPException(status_code=404, detail="Room not found.")
        response = RoomDetailResponseSchema(
            **RoomResponseSchema.model_validate(room).model_dump(),
            hoc_sinh_list=[StudentResponseSchema.model_validate(student) for student in room.hoc_sinh_list],
        )
        response.hoc_sinh_list.sort(key=lambda student: (student.ho_va_ten, student.id_hs))
        rows = db.execute(select(
            HocSinh.id_hs, HocSinh.ho_va_ten, HocSinh.ma_code_phong_thi,
            KetQuaThi.trang_thai, KetQuaThi.diem_so,
        ).join(KetQuaThi, KetQuaThi.id_hs == HocSinh.id_hs).where(
            KetQuaThi.id_phong_thi == room.id_phong_thi,
            HocSinh.ma_code_phong_thi == room.ma_code_phong_thi,
        )).all()
        response.ket_qua_list = [StudentScoreSchema.model_validate({
            **row._mapping, "diem_so": row.diem_so if row.trang_thai == TrangThaiBaiLam.HOAN_THANH else None,
        }) for row in rows]
        return response
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Room database unavailable.") from exc


def join_room(db: Session, payload: StudentJoinSchema) -> StudentJoinResponseSchema:
    try:
        room = db.scalar(select(PhongThi).where(
            PhongThi.ma_code_phong_thi == payload.ma_code_phong_thi,
        ).with_for_update())
        if room is None:
            raise HTTPException(status_code=404, detail="Room not found.")
        if room.trang_thai_phong not in (TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DANG_THI):
            raise HTTPException(status_code=409, detail="Room is closed or unavailable.")

        student = HocSinh(ho_va_ten=payload.ho_va_ten, ma_code_phong_thi=room.ma_code_phong_thi)
        db.add(student)
        db.flush()  # Assign student ID; this is not a commit.
        result = KetQuaThi(
            id_hs=student.id_hs, id_phong_thi=room.id_phong_thi,
            trang_thai=TrangThaiBaiLam.DANG_THI, diem_so=0.0,
        )
        db.add(result)
        db.flush()
        response = StudentJoinResponseSchema(
            id_hs=student.id_hs, ho_va_ten=student.ho_va_ten,
            ma_code_phong_thi=room.ma_code_phong_thi, id_phong_thi=room.id_phong_thi,
            ten_lop=room.ten_lop, thoi_gian_lam_bai=room.thoi_gian_lam_bai,
        )
        db.commit()  # Student + result are persisted together, exactly once.
        return response
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(status_code=409, detail="Student admission conflicts with existing records.") from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Room database unavailable.") from exc
    except Exception:
        db.rollback()
        raise
