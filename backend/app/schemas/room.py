"""Room DTOs: snake_case ORM attributes, camelCase JSON contracts."""

from datetime import date
from typing import Annotated, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from pydantic.alias_generators import to_camel

from app.models.models import TrangThaiBaiLam, TrangThaiPhongThi

NonEmptyText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
RoomCode = Annotated[str, StringConstraints(pattern=r"^[A-Z0-9]{6}$", min_length=6, max_length=6)]


class RoomSchema(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, from_attributes=True,
        extra="forbid", strict=True,
    )


class RoomCreateSchema(RoomSchema):
    id_de_thi: NonEmptyText
    ten_lop: NonEmptyText
    thoi_gian_lam_bai: Annotated[int, Field(ge=1, le=2_147_483_647)]


class RoomResponseSchema(RoomSchema):
    id_phong_thi: str
    ma_code_phong_thi: RoomCode
    ten_lop: str
    thoi_gian_lam_bai: int
    trang_thai_phong: Optional[TrangThaiPhongThi]
    ngay_tao_phong: Optional[date]


class StudentJoinSchema(RoomSchema):
    ho_va_ten: NonEmptyText
    ma_code_phong_thi: RoomCode


class StudentJoinResponseSchema(RoomSchema):
    id_hs: str
    ho_va_ten: str
    ma_code_phong_thi: RoomCode
    id_phong_thi: str
    ten_lop: str
    thoi_gian_lam_bai: int


class StudentResponseSchema(RoomSchema):
    id_hs: str
    ho_va_ten: str
    ma_code_phong_thi: RoomCode


class StudentScoreSchema(StudentResponseSchema):
    trang_thai: Optional[TrangThaiBaiLam]
    diem_so: Optional[Annotated[float, Field(ge=0, le=10, allow_inf_nan=False)]]


class RoomDetailResponseSchema(RoomResponseSchema):
    hoc_sinh_list: list[StudentResponseSchema]
    ket_qua_list: list[StudentScoreSchema] = Field(default_factory=list)


class RoomClosedSchema(RoomSchema):
    id_phong_thi: str
    ma_code_phong_thi: RoomCode
    trang_thai_phong: Literal["DA_DONG"] = "DA_DONG"
