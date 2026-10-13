"""Room routes: teacher-only creation/roster; anonymous student admission."""

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.room_dependencies import get_room_teacher
from app.core.security import get_current_teacher
from app.models.models import GiaoVien
from app.schemas.room import (
    RoomCreateSchema, RoomDetailResponseSchema, RoomResponseSchema,
    StudentJoinResponseSchema, StudentJoinSchema,
)
from app.services import room_service

router = APIRouter(prefix="/rooms", tags=["Exam rooms"])


@router.post("", response_model=RoomResponseSchema, status_code=status.HTTP_201_CREATED)
def create(
    payload: RoomCreateSchema, teacher: GiaoVien = Depends(get_room_teacher),
    db: Session = Depends(get_db),
) -> RoomResponseSchema:
    return room_service.create_room(db, teacher.id_gv, payload)


@router.post("/join", response_model=StudentJoinResponseSchema, status_code=status.HTTP_201_CREATED)
def join(payload: StudentJoinSchema, db: Session = Depends(get_db)) -> StudentJoinResponseSchema:
    return room_service.join_room(db, payload)


@router.get("/{id_phong_thi}", response_model=RoomDetailResponseSchema)
def get_detail(
    id_phong_thi: str, teacher: GiaoVien = Depends(get_current_teacher),
    db: Session = Depends(get_db),
) -> RoomDetailResponseSchema:
    return room_service.get_room(db, teacher.id_gv, id_phong_thi)
