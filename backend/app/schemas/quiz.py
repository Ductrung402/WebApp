"""Six existing CauHoi content columns; IDs are assigned by the database layer."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, StringConstraints

QuestionText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class QuizQuestionSchema(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    noi_dung: QuestionText
    dap_an_a: QuestionText
    dap_an_b: QuestionText
    dap_an_c: QuestionText
    dap_an_d: QuestionText
    dap_an_dung: Literal["A", "B", "C", "D"]
