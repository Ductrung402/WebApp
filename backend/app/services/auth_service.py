"""Teacher registration and login business logic."""

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.security import create_access_token, hash_password, verify_password
from app.models.models import GiaoVien
from app.schemas.auth import TeacherLoginSchema, TeacherRegisterSchema, TeacherResponseSchema, TokenSchema

_dummy_password_hash = hash_password("teacher-auth-dummy-password")


def register_teacher(db: Session, payload: TeacherRegisterSchema) -> TeacherResponseSchema:
    try:
        existing_teacher = db.scalar(select(GiaoVien).where(func.lower(GiaoVien.email) == payload.email))
        if existing_teacher is not None:
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already registered.")
        teacher = GiaoVien(
            email=str(payload.email), mat_khau=hash_password(payload.mat_khau),
            ho_ten=payload.ho_ten, truong=payload.truong, tinh=payload.tinh,
        )
        db.add(teacher)
        db.flush()
        response = TeacherResponseSchema.model_validate(teacher)
        db.commit()
        return response
    except IntegrityError as exc:
        db.rollback()
        constraint = getattr(getattr(exc.orig, "diag", None), "constraint_name", "") or ""
        if "email" in constraint or "giao_vien.email" in str(exc.orig):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already registered.") from exc
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Unable to register teacher.") from exc
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Unable to register teacher.") from exc
    except Exception:
        db.rollback()
        raise


def login_teacher(db: Session, payload: TeacherLoginSchema) -> TokenSchema:
    try:
        teacher = db.scalar(select(GiaoVien).where(func.lower(GiaoVien.email) == payload.email))
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Authentication database unavailable.") from exc
    password_hash = teacher.mat_khau if teacher is not None else _dummy_password_hash
    password_is_valid = verify_password(payload.mat_khau, password_hash)
    if teacher is None or not password_is_valid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect email or password.",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return TokenSchema(access_token=create_access_token({"sub": teacher.id_gv}))
