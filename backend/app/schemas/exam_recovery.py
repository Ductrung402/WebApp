"""Disconnect/reconnect packets; public exam schema never contains answer keys."""

from typing import Annotated, Optional

from pydantic import Field

from app.schemas.exam_flow import ExamRoomEventSchema, ExamSyncSchema
from app.schemas.room import NonEmptyText


class ReconnectExamSchema(ExamRoomEventSchema):
    id_hs: NonEmptyText


class ReconnectExamResponseSchema(ExamSyncSchema):
    remaining_seconds: Optional[Annotated[int, Field(ge=0)]] = None
    exam_expired: bool = False
