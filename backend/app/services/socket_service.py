"""Targeted, read-only Socket.IO admission checks, called in a worker thread."""

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError

from app.core.database import SessionLocal
from app.core.security import get_current_teacher
from app.models.models import HocSinh, PhongThi, TrangThaiPhongThi
from app.schemas.room import StudentResponseSchema
from app.schemas.socket import SocketJoinSchema, SocketMembershipSchema


def authorize_socket_join(payload: SocketJoinSchema, token: str | None) -> SocketMembershipSchema:
    with SessionLocal() as db:
        try:
            room = db.execute(select(
                PhongThi.id_phong_thi, PhongThi.id_gv, PhongThi.trang_thai_phong,
            ).where(PhongThi.ma_code_phong_thi == payload.ma_code_phong_thi).limit(1)).one_or_none()
            if room is None:
                raise HTTPException(status_code=404, detail="Room not found.")
            if room.trang_thai_phong not in (TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DANG_THI):
                raise HTTPException(status_code=409, detail="Room is closed or unavailable.")
            student = None
            if payload.user_type == "teacher":
                if not token:
                    raise HTTPException(status_code=401, detail="Teacher authentication required.")
                teacher = get_current_teacher(token, db)
                if teacher.id_gv != room.id_gv:
                    raise HTTPException(status_code=403, detail="Teacher does not own this room.")
            else:
                row = db.execute(select(
                    HocSinh.id_hs, HocSinh.ho_va_ten, HocSinh.ma_code_phong_thi,
                ).where(
                    HocSinh.id_hs == payload.id_hs,
                    HocSinh.ma_code_phong_thi == payload.ma_code_phong_thi,
                ).limit(1)).one_or_none()
                if row is None:
                    raise HTTPException(status_code=403, detail="Student is not admitted to this room.")
                student = StudentResponseSchema.model_validate(row._mapping)
            return SocketMembershipSchema(
                **payload.model_dump(), id_phong_thi=room.id_phong_thi, student=student,
            )
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="Room database unavailable.") from exc
