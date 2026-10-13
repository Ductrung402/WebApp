"""Atomic runtime sidecars, keeping the fixed SQL schema untouched."""

import hashlib
import os
from pathlib import Path
import tempfile
import threading

from app.schemas.exam_flow import ExamStartedSchema, StoredSubmissionSchema
from app.schemas.socket import StudentRecoveryStateSchema
from app.schemas.submission import StoredGradeSchema


class ExamRuntimeStore:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.lock = threading.RLock()

    def _write(self, path, model):
        self.directory.mkdir(parents=True, exist_ok=True)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=self.directory, delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(model.model_dump_json(by_alias=True))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
        finally:
            if temporary and temporary.exists():
                temporary.unlink()

    def read_run(self, code):
        # Codes passed here have already been checked by RoomCode/Pydantic.
        path = self.directory / f"run-{code}.json"
        with self.lock:
            return ExamStartedSchema.model_validate_json(path.read_text(encoding="utf-8")) if path.exists() else None

    def write_run(self, run):
        with self.lock:
            self._write(self.directory / f"run-{run.ma_code_phong_thi}.json", run)

    def _submission_path(self, run_id, student_id):
        digest = hashlib.sha256(f"{run_id}:{student_id}".encode()).hexdigest()
        return self.directory / f"submission-{digest}.json"

    def read_submission(self, run_id, student_id):
        with self.lock:
            path = self._submission_path(run_id, student_id)
            return StoredSubmissionSchema.model_validate_json(path.read_text(encoding="utf-8")) if path.exists() else None

    def receive_submission(self, submission):
        with self.lock:
            existing = self.read_submission(submission.run_id, submission.id_hs)
            if existing:
                return existing.model_copy(update={"duplicate": True})
            self._write(self._submission_path(submission.run_id, submission.id_hs), submission)
            return submission

    def _recovery_path(self, room_id, student_id):
        digest = hashlib.sha256(f"{room_id}:{student_id}".encode()).hexdigest()
        return self.directory / f"recovery-{digest}.json"

    def read_recovery(self, room_id, student_id):
        with self.lock:
            path = self._recovery_path(room_id, student_id)
            return StudentRecoveryStateSchema.model_validate_json(path.read_text(encoding="utf-8")) if path.exists() else None

    def write_recovery(self, state):
        with self.lock:
            self._write(self._recovery_path(state.id_phong_thi, state.id_hs), state)

    def _grade_path(self, room_id, student_id):
        digest = hashlib.sha256(f"{room_id}:{student_id}".encode()).hexdigest()
        return self.directory / f"grade-{digest}.json"

    def read_grade(self, room_id, student_id):
        with self.lock:
            path = self._grade_path(room_id, student_id)
            return StoredGradeSchema.model_validate_json(path.read_text(encoding="utf-8")) if path.exists() else None

    def write_grade(self, grade):
        with self.lock:
            self._write(self._grade_path(grade.id_phong_thi, grade.result.id_hs), grade)


runtime_store = ExamRuntimeStore(Path(__file__).resolve().parents[2] / ".runtime")
