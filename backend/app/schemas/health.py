"""Response contract for the application health endpoint."""

from typing import Literal

from pydantic import BaseModel


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    app: Literal["Quiz AI & Exam System"] = "Quiz AI & Exam System"
