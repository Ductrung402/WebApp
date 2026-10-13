"""Validated Socket.IO packets with the same camelCase convention as REST."""

from typing import Annotated, Literal, Optional

from pydantic import Field, model_validator

from app.schemas.room import NonEmptyText, RoomCode, RoomSchema, StudentResponseSchema


class SocketAuthSchema(RoomSchema):
    token: Optional[NonEmptyText] = None


class SocketJoinSchema(RoomSchema):
    ma_code_phong_thi: RoomCode
    user_type: Literal["teacher", "student"]
    id_hs: Optional[NonEmptyText] = None

    @model_validator(mode="after")
    def validate_identity(self):
        if self.user_type == "student" and self.id_hs is None:
            raise ValueError("Students must supply id_hs from their anonymous admission.")
        if self.user_type == "teacher" and self.id_hs is not None:
            raise ValueError("Teacher packets must not specify a student identity.")
        return self


class SocketMembershipSchema(SocketJoinSchema):
    id_phong_thi: str
    student: Optional[StudentResponseSchema] = None


class SocketJoinAckSchema(SocketJoinSchema):
    status: Literal["ok"] = "ok"
    id_phong_thi: str


class SocketErrorSchema(RoomSchema):
    status: Literal["error"] = "error"
    status_code: int
    detail: str


class SocketStudentsSchema(RoomSchema):
    ma_code_phong_thi: RoomCode
    hoc_sinh_list: list[StudentResponseSchema]


class StudentRecoveryStateSchema(StudentResponseSchema):
    id_phong_thi: str
    trang_thai: Literal["DANG_THI", "MAT_KET_NOI", "HOAN_THANH"]
    state_version: Annotated[int, Field(ge=0)] = 0
    state_changed_at: Annotated[int, Field(ge=0)] = 0
    disconnected_at: Optional[Annotated[int, Field(ge=0)]] = None
    frozen_remaining_seconds: Optional[Annotated[int, Field(ge=0)]] = None


class SocketStudentStatesSchema(RoomSchema):
    ma_code_phong_thi: RoomCode
    hoc_sinh_list: list[StudentRecoveryStateSchema]


class StudentRecoveryErrorSchema(SocketErrorSchema):
    ma_code_phong_thi: RoomCode
    id_hs: str
