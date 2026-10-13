"""Exam contracts: camelCase JSON aliases and existing snake_case ORM attributes."""

from datetime import date
from typing import Annotated, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from pydantic.alias_generators import to_camel

from app.schemas.quiz import QuestionText, QuizQuestionSchema


class ExamSchema(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, from_attributes=True,
        extra="forbid", strict=True,
    )


class CauHoiCreateSchema(QuizQuestionSchema):
    model_config = ExamSchema.model_config

    @model_validator(mode="after")
    def distinct_options(self):
        options = [self.dap_an_a, self.dap_an_b, self.dap_an_c, self.dap_an_d]
        if len({option.casefold() for option in options}) != 4:
            raise ValueError("Four distinct answer options are required.")
        return self


class CauHoiResponseSchema(CauHoiCreateSchema):
    id_cau_hoi: str
    id_de_thi: str


class DeThiInputSchema(ExamSchema):
    ten_de_thi: QuestionText
    khoi: Optional[QuestionText] = None


class DeThiCreateManualSchema(DeThiInputSchema):
    kieu_tai_lieu: Literal["TEXT", "FILE"] = "TEXT"
    noi_dung_tai_lieu: Optional[str] = None
    cau_hoi_list: Annotated[List[CauHoiCreateSchema], Field(min_length=1)]


class DeThiGenerateAISchema(DeThiInputSchema):
    raw_text: Annotated[str, Field(min_length=1)]
    num_questions: Annotated[int, Field(ge=1)] = 10

    @field_validator("raw_text")
    @classmethod
    def non_blank_source(cls, raw_text: str) -> str:
        if not raw_text.strip():
            raise ValueError("raw_text must not be blank.")
        return raw_text


class DeThiListResponseSchema(ExamSchema):
    id_de_thi: str
    id_gv: str
    ten_de_thi: str
    khoi: Optional[str]
    so_luong_cau_hoi: Optional[int]
    kieu_tai_lieu: str
    ngay_tao: Optional[date]


class DeThiDetailResponseSchema(DeThiListResponseSchema):
    noi_dung_tai_lieu: Optional[str]
    cau_hoi_list: List[CauHoiResponseSchema]


class ExamDeleteResponseSchema(ExamSchema):
    id_de_thi: str
    status: Literal["deleted"] = "deleted"
