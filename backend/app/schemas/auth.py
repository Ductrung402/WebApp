"""Teacher authentication contracts matching GiaoVien's existing columns."""

from typing import Annotated, Literal, Optional

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    EmailStr,
    Field,
    StringConstraints,
    field_validator,
)


def validate_password(password: str) -> str:
    if len(password.encode("utf-8")) > 72 or "\x00" in password:
        raise ValueError("Password must not exceed 72 UTF-8 bytes or contain null bytes.")
    return password


Password = Annotated[
    str, Field(min_length=8, max_length=72, repr=False), AfterValidator(validate_password)
]
NonEmptyString = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class TeacherLoginSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: EmailStr
    mat_khau: Password

    @field_validator("email")
    @classmethod
    def normalize_email(cls, email: str) -> str:
        return email.lower()


class TeacherRegisterSchema(TeacherLoginSchema):
    ho_ten: NonEmptyString
    truong: Optional[NonEmptyString] = None
    tinh: Optional[NonEmptyString] = None


class TeacherResponseSchema(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id_gv: str
    email: EmailStr
    ho_ten: str
    truong: Optional[str] = None
    tinh: Optional[str] = None


class TokenSchema(BaseModel):
    access_token: str
    token_type: Literal["bearer"] = "bearer"


class TeacherUpdateProfileSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    ho_ten: NonEmptyString
    truong: Optional[str] = None
    tinh: Optional[str] = None

    @field_validator("truong", "tinh")
    @classmethod
    def normalize_optional_text(cls, value: Optional[str]) -> Optional[str]:
        return value.strip() or None if value is not None else None


class ChangePasswordSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mat_khau_cu: Password
    mat_khau_moi: Password


class ChangePasswordResponseSchema(BaseModel):
    status: Literal["ok"] = "ok"
    message: Literal["Đã cập nhật mật khẩu."] = "Đã cập nhật mật khẩu."
