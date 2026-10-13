"""Anonymous submission route; validation and grading stay in schema/service."""

from typing import Annotated

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.schemas.submission import ExamSubmissionSchema, SubmissionLookupSchema, SubmissionResultResponseSchema
from app.services import grading_service

router = APIRouter(prefix="/submissions", tags=["Exam submissions"])


@router.get("/result", response_model=SubmissionResultResponseSchema)
def result(payload: Annotated[SubmissionLookupSchema, Query()], db: Session = Depends(get_db)) -> SubmissionResultResponseSchema:
    return grading_service.get_student_result(db, payload)


@router.post("/submit", response_model=SubmissionResultResponseSchema)
async def submit(payload: ExamSubmissionSchema, db: Session = Depends(get_db)) -> SubmissionResultResponseSchema:
    return await grading_service.submit_and_notify(db, payload)
