"""Teacher registration and login business logic."""

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.security import create_access_token, hash_password, verify_password
from app.models.models import GiaoVien
from app.schemas.auth import ChangePasswordResponseSchema, ChangePasswordSchema, TeacherLoginSchema, TeacherRegisterSchema, TeacherResponseSchema, TeacherUpdateProfileSchema, TokenSchema

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


def update_teacher_profile(
    db: Session, teacher_id: str, payload: TeacherUpdateProfileSchema,
) -> TeacherResponseSchema:
    try:
        teacher = db.scalar(
            select(GiaoVien).where(GiaoVien.id_gv == teacher_id)
            .with_for_update().execution_options(populate_existing=True)
        )
        if teacher is None:
            raise HTTPException(status_code=404, detail="Teacher account not found.")
        teacher.ho_ten = payload.ho_ten
        teacher.truong = payload.truong
        teacher.tinh = payload.tinh
        db.flush()
        response = TeacherResponseSchema.model_validate(teacher)
        db.commit()
        return response
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Unable to update teacher profile.") from exc
    except Exception:
        db.rollback()
        raise


def change_teacher_password(
    db: Session, teacher_id: str, payload: ChangePasswordSchema,
) -> ChangePasswordResponseSchema:
    try:
        teacher = db.scalar(
            select(GiaoVien).where(GiaoVien.id_gv == teacher_id)
            .with_for_update().execution_options(populate_existing=True)
        )
        if teacher is None:
            raise HTTPException(status_code=404, detail="Teacher account not found.")
        if not verify_password(payload.mat_khau_cu, teacher.mat_khau):
            raise HTTPException(status_code=400, detail="Mật khẩu hiện tại không chính xác.")
        teacher.mat_khau = hash_password(payload.mat_khau_moi)
        db.flush()
        response = ChangePasswordResponseSchema()
        db.commit()
        return response
    except SQLAlchemyError as exc:
        db.rollback()
        raise HTTPException(status_code=503, detail="Unable to change teacher password.") from exc
    except Exception:
        db.rollback()
        raise


# Keep the existing service import available for callers of Task 6.5.
change_password = change_teacher_password
