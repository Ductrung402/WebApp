"""Strict Pydantic v2 contracts; camelCase JSON, snake_case ORM attributes."""

from typing import Annotated, Literal, Optional

from pydantic import Field, model_validator

from app.models.models import TrangThaiBaiLam
from app.schemas.room import NonEmptyText, RoomCode, RoomSchema


class AnswerItem(RoomSchema):
    id_cau_hoi: NonEmptyText
    dap_an_chon: Optional[Literal["A", "B", "C", "D"]]


class ExamSubmissionSchema(RoomSchema):
    id_hs: NonEmptyText
    id_phong_thi: NonEmptyText
    danh_sach_cau_tra_loi: list[AnswerItem]

    @model_validator(mode="after")
    def unique_question_ids(self):
        ids = [answer.id_cau_hoi for answer in self.danh_sach_cau_tra_loi]
        if len(ids) != len(set(ids)):
            raise ValueError("Question IDs must not be duplicated.")
        return self


class SubmissionLookupSchema(RoomSchema):
    id_hs: NonEmptyText
    id_phong_thi: NonEmptyText


class SubmissionResultResponseSchema(RoomSchema):
    id_hs: str
    diem_so: Annotated[float, Field(ge=0, le=10, allow_inf_nan=False)]
    so_cau_dung: Annotated[int, Field(ge=0)]
    tong_so_cau: Annotated[int, Field(ge=1)]
    trang_thai: TrangThaiBaiLam

    @model_validator(mode="after")
    def valid_result(self):
        if self.trang_thai != TrangThaiBaiLam.HOAN_THANH:
            raise ValueError("A grading result must be HOAN_THANH.")
        if self.so_cau_dung > self.tong_so_cau:
            raise ValueError("Correct count exceeds the question count.")
        return self


class StoredGradeSchema(RoomSchema):
    id_phong_thi: NonEmptyText
    run_id: NonEmptyText
    result: SubmissionResultResponseSchema


class StudentSubmittedEventSchema(SubmissionResultResponseSchema):
    id_phong_thi: str
    ma_code_phong_thi: RoomCode
    ho_va_ten: str
