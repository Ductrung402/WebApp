"""Public exam packets and ungraded submission contracts. No answer-key field."""

from typing import Annotated, Literal, Optional

from pydantic import Field, model_validator

from app.schemas.room import NonEmptyText, RoomCode, RoomSchema
from app.schemas.socket import StudentRecoveryStateSchema

Millis = Annotated[int, Field(ge=0)]


class ExamRoomEventSchema(RoomSchema):
    ma_code_phong_thi: RoomCode


class StudentQuestionSchema(RoomSchema):
    id_cau_hoi: NonEmptyText
    noi_dung: NonEmptyText
    dap_an_a: NonEmptyText
    dap_an_b: NonEmptyText
    dap_an_c: NonEmptyText
    dap_an_d: NonEmptyText


class ExamStartedSchema(ExamRoomEventSchema):
    run_id: NonEmptyText
    id_phong_thi: str
    id_de_thi: str
    ten_de_thi: str
    ten_lop: str
    thoi_gian_lam_bai: Annotated[int, Field(ge=1, le=2147483647)]
    trang_thai_phong: Literal["DANG_THI"] = "DANG_THI"
    starts_at: Millis
    ends_at: Millis
    server_time: Millis
    cau_hoi_list: Annotated[list[StudentQuestionSchema], Field(min_length=1)]

    @model_validator(mode="after")
    def valid_deadline(self):
        if self.ends_at - self.starts_at != self.thoi_gian_lam_bai * 60000:
            raise ValueError("Deadline must match the configured duration.")
        if len({question.id_cau_hoi for question in self.cau_hoi_list}) != len(self.cau_hoi_list):
            raise ValueError("Question IDs must be unique.")
        return self


class ExamSubmitSchema(ExamRoomEventSchema):
    run_id: NonEmptyText
    answers: dict[NonEmptyText, Literal["A", "B", "C", "D"]]
    reason: Literal["manual", "timeout"]


class SubmissionReceiptSchema(RoomSchema):
    submission_id: str
    run_id: str
    id_hs: str
    received_at: Millis
    received_late: bool
    reason: Literal["manual", "timeout"]
    duplicate: bool = False


class StoredSubmissionSchema(SubmissionReceiptSchema):
    answers: dict[str, Literal["A", "B", "C", "D"]]


class ExamSyncSchema(RoomSchema):
    status: Literal["ok"] = "ok"
    server_time: Millis
    request_received_at: Optional[Millis] = None
    exam: Optional[ExamStartedSchema] = None
    submission: Optional[SubmissionReceiptSchema] = None
    student_state: Optional[StudentRecoveryStateSchema] = None
