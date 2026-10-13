"""Authenticated API tests with real ORM transactions in isolated SQLite."""

from datetime import date
import os
import unittest
from unittest.mock import patch

os.environ["JWT_SECRET_KEY"] = "test-only-key-never-use-outside-tests-123456789"

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.config import get_auth_settings
from app.core.database import get_db
from app.core.security import create_access_token, hash_password
from app.models.models import Base, CauHoi, DeThi, GiaoVien, PhongThi
from app.schemas.exam import CauHoiResponseSchema, DeThiDetailResponseSchema
from app.services import exam_service, gemini_service
from main import fastapi_app as app

QUESTION = {
    "noi_dung": "Công thức hóa học của nước là gì?", "dap_an_a": "H2O",
    "dap_an_b": "CO2", "dap_an_c": "O2", "dap_an_d": "NaCl", "dap_an_dung": "A",
}
SECOND_QUESTION = {**QUESTION, "noi_dung": "Chọn công thức của nước."}
FIXTURE_PASSWORD_HASH = hash_password("ExamTestPass123!")


class ExamApiTests(unittest.TestCase):
    def setUp(self):
        get_auth_settings.cache_clear()
        self.engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
        @event.listens_for(self.engine, "connect")
        def enable_foreign_keys(connection, record):
            connection.execute("PRAGMA foreign_keys=ON")
        Base.metadata.create_all(self.engine)
        self.factory = sessionmaker(bind=self.engine)
        with self.factory() as db:
            db.add_all([
                GiaoVien(id_gv="owner-id", email="owner@example.com", mat_khau=FIXTURE_PASSWORD_HASH, ho_ten="Owner"),
                GiaoVien(id_gv="other-id", email="other@example.com", mat_khau=FIXTURE_PASSWORD_HASH, ho_ten="Other"),
            ])
            db.commit()
        def override_db():
            with self.factory() as db:
                yield db
        app.dependency_overrides[get_db] = override_db
        self.client = TestClient(app)
        self.client.__enter__()
        self.headers = {"Authorization": "Bearer " + create_access_token({"sub": "owner-id"})}
        self.other_headers = {"Authorization": "Bearer " + create_access_token({"sub": "other-id"})}
        self.manual = {
            "tenDeThi": "Kiến thức về nước", "khoi": "6", "kieuTaiLieu": "TEXT",
            "noiDungTaiLieu": "Nước có công thức H2O.", "cauHoiList": [QUESTION, SECOND_QUESTION],
        }
        self.ai_payload = {"tenDeThi": "Đề AI", "khoi": "6", "rawText": "Nước có công thức H2O.", "numQuestions": 2}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        app.dependency_overrides.clear()
        self.engine.dispose()

    def create_manual(self, headers=None):
        response = self.client.post("/api/exams", headers=headers or self.headers, json=self.manual)
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()

    def counts(self):
        with self.factory() as db:
            return tuple(db.scalar(select(func.count()).select_from(model)) for model in [DeThi, CauHoi])

    def test_manual_create_list_detail_update_delete_cascade(self):
        exam = self.create_manual()
        exam_id = exam["idDeThi"]
        self.assertEqual(exam["idGv"], "owner-id")
        self.assertEqual(exam["soLuongCauHoi"], 2)
        self.assertEqual(exam["ngayTao"], date.today().isoformat())
        old_question_ids = {question["idCauHoi"] for question in exam["cauHoiList"]}
        self.assertTrue(all(question["idDeThi"] == exam_id for question in exam["cauHoiList"]))
        self.assertEqual(self.counts(), (1, 2))
        detail = self.client.get("/api/exams/" + exam_id, headers=self.headers)
        self.assertEqual(detail.status_code, 200)
        self.assertEqual(detail.json(), exam)
        listed = self.client.get("/api/exams", headers=self.headers)
        self.assertEqual(listed.status_code, 200)
        self.assertEqual(len(listed.json()), 1)
        self.assertNotIn("cauHoiList", listed.json()[0])
        self.assertNotIn("noiDungTaiLieu", listed.json()[0])
        updated = self.client.put("/api/exams/" + exam_id, headers=self.headers, json={**self.manual, "tenDeThi": "Đề đã sửa", "cauHoiList": [QUESTION]})
        self.assertEqual(updated.status_code, 200, updated.text)
        self.assertEqual(updated.json()["soLuongCauHoi"], 1)
        self.assertEqual(updated.json()["tenDeThi"], "Đề đã sửa")
        self.assertEqual(self.counts(), (1, 1))
        with self.factory() as db:
            self.assertEqual(list(db.scalars(select(CauHoi).where(CauHoi.id_cau_hoi.in_(old_question_ids)))), [])
        deleted = self.client.delete("/api/exams/" + exam_id, headers=self.headers)
        self.assertEqual(deleted.status_code, 200, deleted.text)
        self.assertEqual(deleted.json(), {"idDeThi": exam_id, "status": "deleted"})
        self.assertEqual(self.counts(), (0, 0))

    def test_ai_create_calls_service_and_commits_once(self):
        original_commit = Session.commit
        with patch.object(gemini_service, "generate_quiz_from_text", return_value=[QUESTION, SECOND_QUESTION]) as ai, patch.object(Session, "commit", autospec=True, side_effect=original_commit) as commit:
            response = self.client.post("/api/exams/generate-ai", headers=self.headers, json=self.ai_payload)
            self.assertEqual(response.status_code, 201, response.text)
            ai.assert_called_once_with(self.ai_payload["rawText"], 2)
            commit.assert_called_once()
        self.assertEqual(response.json()["kieuTaiLieu"], "TEXT")
        self.assertEqual(response.json()["noiDungTaiLieu"], self.ai_payload["rawText"])
        self.assertEqual(self.counts(), (1, 2))

    def test_ai_preview_returns_draft_without_inserting_or_committing(self):
        with patch.object(gemini_service, "generate_quiz_from_text", return_value=[QUESTION, SECOND_QUESTION]), patch.object(Session, "commit", side_effect=AssertionError("Preview must not commit")), patch.object(Session, "add", side_effect=AssertionError("Preview must not add records")):
            response = self.client.post("/api/exams/preview-ai", headers=self.headers, json=self.ai_payload)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(len(response.json()["cauHoiList"]), 2)
        self.assertEqual(response.json()["tenDeThi"], self.ai_payload["tenDeThi"])
        self.assertNotIn("idDeThi", response.json())
        self.assertNotIn("idCauHoi", response.json()["cauHoiList"][0])
        self.assertEqual(self.counts(), (0, 0))

    def test_ai_preview_failure_rolls_back_and_never_saves(self):
        original_rollback = Session.rollback
        for error, code in [(gemini_service.GeminiTimeoutError("timeout"), 503), (gemini_service.GeminiResponseError("invalid JSON"), 400)]:
            with self.subTest(error=type(error).__name__), patch.object(gemini_service, "generate_quiz_from_text", side_effect=error), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
                response = self.client.post("/api/exams/preview-ai", headers=self.headers, json=self.ai_payload)
                self.assertEqual(response.status_code, code)
                rollback.assert_called_once()
            self.assertEqual(self.counts(), (0, 0))

    def test_ai_preview_requires_auth_and_validates_before_database_query(self):
        self.assertEqual(self.client.post("/api/exams/preview-ai", json=self.ai_payload).status_code, 401)
        with patch.object(Session, "_execute_internal", side_effect=AssertionError("Invalid preview queried DB")):
            self.assertEqual(self.client.post("/api/exams/preview-ai", headers=self.headers, json={**self.ai_payload, "rawText": " "}).status_code, 422)

    def test_ai_timeout_and_invalid_json_rollback_without_empty_exam(self):
        original_rollback = Session.rollback
        for error, code in [(gemini_service.GeminiTimeoutError("timeout"), 503), (gemini_service.GeminiResponseError("invalid JSON"), 400)]:
            with self.subTest(error=type(error).__name__), patch.object(gemini_service, "generate_quiz_from_text", side_effect=error), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
                response = self.client.post("/api/exams/generate-ai", headers=self.headers, json=self.ai_payload)
                self.assertEqual(response.status_code, code, response.text)
                rollback.assert_called_once()
            self.assertEqual(self.counts(), (0, 0))

    def test_invalid_generated_question_and_wrong_count_reject_whole_exam(self):
        for questions in [[QUESTION, {**SECOND_QUESTION, "dap_an_dung": "E"}], [QUESTION]]:
            with self.subTest(questions=questions), patch.object(gemini_service, "generate_quiz_from_text", return_value=questions):
                response = self.client.post("/api/exams/generate-ai", headers=self.headers, json=self.ai_payload)
                self.assertEqual(response.status_code, 400, response.text)
            self.assertEqual(self.counts(), (0, 0))

    def test_failure_after_question_inserts_rolls_back_parent_and_all_children(self):
        inserted = []
        def fail_after_insert(mapper, connection, target):
            inserted.append(connection.scalar(select(func.count()).select_from(CauHoi)))
            raise SQLAlchemyError("Simulated child write failure after INSERT")
        event.listen(CauHoi, "after_insert", fail_after_insert)
        try:
            with patch.object(gemini_service, "generate_quiz_from_text", return_value=[QUESTION, SECOND_QUESTION]):
                response = self.client.post("/api/exams/generate-ai", headers=self.headers, json=self.ai_payload)
            self.assertEqual(response.status_code, 503, response.text)
        finally:
            event.remove(CauHoi, "after_insert", fail_after_insert)
        self.assertTrue(inserted and inserted[0] > 0)
        self.assertEqual(self.counts(), (0, 0))

    def test_commit_failure_rolls_back_exam_and_questions(self):
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("commit failure")):
            response = self.client.post("/api/exams", headers=self.headers, json=self.manual)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.counts(), (0, 0))

    def test_update_failure_restores_original_metadata_and_questions(self):
        exam = self.create_manual()
        def fail_after_insert(mapper, connection, target):
            raise SQLAlchemyError("new question insert failed")
        event.listen(CauHoi, "after_insert", fail_after_insert)
        try:
            response = self.client.put("/api/exams/" + exam["idDeThi"], headers=self.headers, json={**self.manual, "tenDeThi": "Failed update", "cauHoiList": [QUESTION]})
            self.assertEqual(response.status_code, 503)
        finally:
            event.remove(CauHoi, "after_insert", fail_after_insert)
        self.assertEqual(self.client.get("/api/exams/" + exam["idDeThi"], headers=self.headers).json(), exam)
        self.assertEqual(self.counts(), (1, 2))

    def test_delete_commit_failure_restores_exam_and_children(self):
        exam = self.create_manual()
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("delete commit failure")):
            response = self.client.delete("/api/exams/" + exam["idDeThi"], headers=self.headers)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.counts(), (1, 2))

    def test_other_teacher_cannot_read_update_or_delete_exam(self):
        exam = self.create_manual()
        exam_id = exam["idDeThi"]
        self.assertEqual(self.client.get("/api/exams", headers=self.other_headers).json(), [])
        for response in [
            self.client.get("/api/exams/" + exam_id, headers=self.other_headers),
            self.client.put("/api/exams/" + exam_id, headers=self.other_headers, json=self.manual),
            self.client.delete("/api/exams/" + exam_id, headers=self.other_headers),
        ]:
            self.assertEqual(response.status_code, 404, response.text)
        self.assertEqual(self.counts(), (1, 2))
        self.create_manual(headers=self.other_headers)
        self.assertEqual(len(self.client.get("/api/exams", headers=self.headers).json()), 1)
        self.assertEqual(len(self.client.get("/api/exams", headers=self.other_headers).json()), 1)

    def test_nonexistent_exam_returns_404(self):
        for response in [
            self.client.get("/api/exams/missing", headers=self.headers),
            self.client.put("/api/exams/missing", headers=self.headers, json=self.manual),
            self.client.delete("/api/exams/missing", headers=self.headers),
        ]:
            self.assertEqual(response.status_code, 404)

    def test_unauthenticated_routes_are_blocked(self):
        for response in [
            self.client.get("/api/exams"), self.client.get("/api/exams/missing"),
            self.client.post("/api/exams", json=self.manual),
            self.client.post("/api/exams/generate-ai", json=self.ai_payload),
            self.client.put("/api/exams/missing", json=self.manual), self.client.delete("/api/exams/missing"),
        ]:
            self.assertEqual(response.status_code, 401)

    def test_invalid_payload_returns_422_before_any_database_query(self):
        invalid_manual = [
            {**self.manual, "tenDeThi": "   "}, {**self.manual, "khoi": 6},
            {**self.manual, "cauHoiList": []}, {**self.manual, "idGv": "other-id"},
            {**self.manual, "soLuongCauHoi": 50},
            {**self.manual, "cauHoiList": [{**QUESTION, "dap_an_d": None}]},
            {**self.manual, "cauHoiList": [{**QUESTION, "dap_an_b": "H2O"}]},
        ]
        invalid_ai = [
            {**self.ai_payload, "rawText": "  "}, {**self.ai_payload, "numQuestions": 0},
            {**self.ai_payload, "numQuestions": "2"}, {**self.ai_payload, "numQuestions": True},
        ]
        with patch.object(Session, "_execute_internal", side_effect=AssertionError("Invalid payload queried DB")), patch.object(gemini_service, "generate_quiz_from_text") as ai:
            for payload in invalid_manual:
                with self.subTest(payload=payload):
                    self.assertEqual(self.client.post("/api/exams", headers=self.headers, json=payload).status_code, 422)
                    self.assertEqual(self.client.put("/api/exams/missing", headers=self.headers, json=payload).status_code, 422)
            for payload in invalid_ai:
                with self.subTest(payload=payload):
                    self.assertEqual(self.client.post("/api/exams/generate-ai", headers=self.headers, json=payload).status_code, 422)
            ai.assert_not_called()

    def test_exam_used_by_room_cannot_be_deleted_or_replaced(self):
        exam = self.create_manual()
        with self.factory() as db:
            db.add(PhongThi(id_gv="owner-id", id_de_thi=exam["idDeThi"], ma_code_phong_thi="ABC123", ten_lop="6A", thoi_gian_lam_bai=30))
            db.commit()
        for response in [
            self.client.delete("/api/exams/" + exam["idDeThi"], headers=self.headers),
            self.client.put("/api/exams/" + exam["idDeThi"], headers=self.headers, json=self.manual),
        ]:
            self.assertEqual(response.status_code, 409)
        self.assertEqual(self.counts(), (1, 2))
        with self.factory() as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(PhongThi)), 1)

    def test_schema_fields_match_model_types_and_nullable_columns(self):
        exam = self.create_manual()
        detail = DeThiDetailResponseSchema.model_validate_json(self.client.get("/api/exams/" + exam["idDeThi"], headers=self.headers).text)
        self.assertIsInstance(detail.ngay_tao, date)
        self.assertIsInstance(detail.so_luong_cau_hoi, int)
        self.assertEqual(set(CauHoiResponseSchema.model_fields), set(CauHoi.__table__.columns.keys()))
        self.assertEqual(set(DeThiDetailResponseSchema.model_fields) - {"cau_hoi_list"}, set(DeThi.__table__.columns.keys()))
        with self.factory() as db:
            model = db.get(DeThi, exam["idDeThi"])
            model.ngay_tao = None
            model.so_luong_cau_hoi = None
            db.commit()
        response = self.client.get("/api/exams/" + exam["idDeThi"], headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["ngayTao"])
        self.assertIsNone(response.json()["soLuongCauHoi"])

    def test_snake_case_input_is_accepted_and_response_uses_camel_case(self):
        payload = {"ten_de_thi": "Snake case", "cau_hoi_list": [QUESTION]}
        response = self.client.post("/api/exams", headers=self.headers, json=payload)
        self.assertEqual(response.status_code, 201, response.text)
        self.assertEqual(response.json()["tenDeThi"], "Snake case")
        self.assertNotIn("ten_de_thi", response.json())
        self.assertIn("noiDung", response.json()["cauHoiList"][0])


if __name__ == "__main__":
    unittest.main()
