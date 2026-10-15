"""HTTP routes for teacher authentication."""

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import get_current_teacher
from app.models.models import GiaoVien
from app.schemas.auth import ChangePasswordResponseSchema, ChangePasswordSchema, TeacherLoginSchema, TeacherRegisterSchema, TeacherResponseSchema, TeacherUpdateProfileSchema, TokenSchema
from app.services.auth_service import change_teacher_password, login_teacher, register_teacher, update_teacher_profile

router = APIRouter(prefix="/auth", tags=["Teacher authentication"])


@router.post("/register", response_model=TeacherResponseSchema, status_code=status.HTTP_201_CREATED)
def register(payload: TeacherRegisterSchema, db: Session = Depends(get_db)) -> TeacherResponseSchema:
    return register_teacher(db, payload)


@router.post("/login", response_model=TokenSchema)
def login(payload: TeacherLoginSchema, db: Session = Depends(get_db)) -> TokenSchema:
    return login_teacher(db, payload)


@router.get("/me", response_model=TeacherResponseSchema)
def get_me(teacher: GiaoVien = Depends(get_current_teacher)) -> TeacherResponseSchema:
    return TeacherResponseSchema.model_validate(teacher)


@router.put("/profile", response_model=TeacherResponseSchema)
def update_profile(
    payload: TeacherUpdateProfileSchema,
    teacher: GiaoVien = Depends(get_current_teacher),
    db: Session = Depends(get_db),
) -> TeacherResponseSchema:
    return update_teacher_profile(db, teacher.id_gv, payload)


@router.put("/change-password", response_model=ChangePasswordResponseSchema)
def update_password(
    payload: ChangePasswordSchema,
    teacher: GiaoVien = Depends(get_current_teacher),
    db: Session = Depends(get_db),
) -> ChangePasswordResponseSchema:
    return change_teacher_password(db, teacher.id_gv, payload)
