"""Teacher-only room close transaction used by the live scoreboard action."""

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError

from app.core.security import get_current_teacher
from app.models.models import PhongThi, TrangThaiPhongThi
from app.schemas.room import RoomClosedSchema
from app.services import socket_service


def close_room(code, token):
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
            packet = RoomClosedSchema(id_phong_thi=room.id_phong_thi, ma_code_phong_thi=room.ma_code_phong_thi)
            if room.trang_thai_phong != TrangThaiPhongThi.DA_DONG:
                room.trang_thai_phong = TrangThaiPhongThi.DA_DONG
                db.flush()
                db.commit()
            return packet
        except SQLAlchemyError as exc:
            db.rollback()
            raise HTTPException(status_code=503, detail="Unable to close the room. Please retry.") from exc
        except Exception:
            db.rollback()
            raise
