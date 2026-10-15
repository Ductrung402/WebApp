"""Authentication integration tests against an isolated, in-memory database."""

from datetime import datetime, timedelta, timezone
import os
import unittest
from unittest.mock import patch

# Use a test-only JWT key before importing the application.
os.environ["JWT_SECRET_KEY"] = "test-only-key-never-use-outside-tests-123456789"

from fastapi.testclient import TestClient
from jose import jwt
from sqlalchemy import create_engine, func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.config import get_auth_settings
from app.core.database import get_db
from app.core.security import create_access_token, hash_password, verify_password
from app.models.models import GiaoVien
from main import fastapi_app as app


class TeacherAuthTests(unittest.TestCase):
    def setUp(self):
        get_auth_settings.cache_clear()
        self.engine = create_engine(
            "sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool,
        )
        # Create only the existing teacher model in the test database.
        GiaoVien.__table__.create(self.engine)
        self.session_factory = sessionmaker(bind=self.engine)

        def override_get_db():
            with self.session_factory() as db:
                yield db

        app.dependency_overrides[get_db] = override_get_db
        self.client = TestClient(app)
        self.client.__enter__()
        self.payload = {
            "email": "teacher@example.com", "mat_khau": "TeacherPass123!",
            "ho_ten": "Nguyen Van A", "truong": "THPT A", "tinh": "TP HCM",
        }

    def tearDown(self):
        self.client.__exit__(None, None, None)
        app.dependency_overrides.clear()
        self.engine.dispose()

    def register(self):
        response = self.client.post("/api/auth/register", json=self.payload)
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()

    def login(self):
        response = self.client.post("/api/auth/login", json={
            "email": self.payload["email"], "mat_khau": self.payload["mat_khau"],
        })
        self.assertEqual(response.status_code, 200, response.text)
        return response.json()

    def test_register_login_me_and_stored_bcrypt_hash(self):
        teacher = self.register()
        self.assertEqual(set(teacher), {"id_gv", "email", "ho_ten", "truong", "tinh"})
        self.assertIsInstance(teacher["id_gv"], str)
        with self.session_factory() as db:
            stored = db.get(GiaoVien, teacher["id_gv"])
            self.assertNotEqual(stored.mat_khau, self.payload["mat_khau"])
            self.assertTrue(stored.mat_khau.startswith("$2b$"))
            self.assertTrue(verify_password(self.payload["mat_khau"], stored.mat_khau))
        token = self.login()
        self.assertEqual(set(token), {"access_token", "token_type"})
        self.assertEqual(token["token_type"], "bearer")
        settings = get_auth_settings()
        claims = jwt.decode(token["access_token"], settings.secret_key, algorithms=[settings.algorithm])
        self.assertEqual(claims["sub"], teacher["id_gv"])
        self.assertGreater(claims["exp"], claims["iat"])
        self.assertEqual(set(claims), {"sub", "exp", "iat"})
        response = self.client.get("/api/auth/me", headers={"Authorization": "Bearer " + token["access_token"]})
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), teacher)
        self.assertNotIn("mat_khau", response.json())

    def test_nullable_school_and_province(self):
        del self.payload["truong"]
        del self.payload["tinh"]
        teacher = self.register()
        self.assertIsNone(teacher["truong"])
        self.assertIsNone(teacher["tinh"])

    def test_duplicate_email_normalization(self):
        self.payload["email"] = "Teacher@EXAMPLE.COM"
        teacher = self.register()
        self.assertEqual(teacher["email"], "teacher@example.com")
        self.payload["email"] = "teacher@example.com"
        self.assertEqual(self.client.post("/api/auth/register", json=self.payload).status_code, 409)
        with self.session_factory() as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(GiaoVien)), 1)

    def test_invalid_payloads_do_not_query_database(self):
        invalid_payloads = [
            {**self.payload, "email": "invalid"},
            {**self.payload, "mat_khau": "short"},
            {**self.payload, "mat_khau": "x" * 73},
            {**self.payload, "mat_khau": "\u00e9" * 37},
            {**self.payload, "mat_khau": "abcdefg\x00h"},
            {**self.payload, "ho_ten": "   "},
            {**self.payload, "truong": 123},
            {**self.payload, "id_gv": "forbidden"},
            {key: value for key, value in self.payload.items() if key != "ho_ten"},
        ]
        with patch.object(Session, "execute", side_effect=AssertionError("Validation touched DB")), patch.object(Session, "_execute_internal", side_effect=AssertionError("Validation touched DB")):
            for payload in invalid_payloads:
                with self.subTest(payload=list(payload)):
                    self.assertEqual(self.client.post("/api/auth/register", json=payload).status_code, 422)
            self.assertEqual(self.client.post("/api/auth/login", json={"email": "invalid", "mat_khau": "validpass"}).status_code, 422)
            self.assertEqual(self.client.post("/api/auth/login", json={"email": "teacher@example.com"}).status_code, 422)

    def test_wrong_password_and_unknown_email(self):
        self.register()
        for email, password in [(self.payload["email"], "WrongPass123!"), ("missing@example.com", "WrongPass123!")]:
            response = self.client.post("/api/auth/login", json={"email": email, "mat_khau": password})
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.headers["www-authenticate"], "Bearer")

    def test_missing_malformed_and_wrong_auth_scheme(self):
        for headers in [{}, {"Authorization": "Bearer broken"}, {"Authorization": "Basic abc"}]:
            response = self.client.get("/api/auth/me", headers=headers)
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.headers["www-authenticate"], "Bearer")

    def test_expired_forged_missing_claims_and_unknown_teacher_tokens(self):
        teacher = self.register()
        settings = get_auth_settings()
        future = datetime.now(timezone.utc) + timedelta(minutes=5)
        tokens = [
            create_access_token({"sub": teacher["id_gv"]}, timedelta(seconds=-60)),
            create_access_token({"sub": "unknown-teacher"}),
            jwt.encode({"sub": teacher["id_gv"], "exp": future}, "wrong-signing-key-12345678901234567890", algorithm="HS256"),
            jwt.encode({"sub": teacher["id_gv"]}, settings.secret_key, algorithm="HS256"),
            jwt.encode({"exp": future}, settings.secret_key, algorithm="HS256"),
            jwt.encode({"sub": "", "exp": future}, settings.secret_key, algorithm="HS256"),
            jwt.encode({"sub": 123, "exp": future}, settings.secret_key, algorithm="HS256"),
            jwt.encode({"sub": teacher["id_gv"], "exp": future}, settings.secret_key, algorithm="HS384"),
        ]
        for index, token in enumerate(tokens):
            with self.subTest(token_case=index):
                response = self.client.get("/api/auth/me", headers={"Authorization": "Bearer " + token})
                self.assertEqual(response.status_code, 401, response.text)

    def test_commit_failure_rolls_back_insert(self):
        original_rollback = Session.rollback
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("simulated commit failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.post("/api/auth/register", json=self.payload)
            self.assertEqual(response.status_code, 503)
            rollback.assert_called_once()
        with self.session_factory() as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(GiaoVien)), 0)
        self.register()

    def test_unique_email_race_rolls_back(self):
        original_flush = Session.flush
        original_rollback = Session.rollback
        def flush_with_concurrent_duplicate(db, *args, **kwargs):
            if db.new:
                raise IntegrityError("INSERT", {}, Exception("UNIQUE constraint failed: giao_vien.email"))
            return original_flush(db, *args, **kwargs)
        with patch.object(Session, "flush", autospec=True, side_effect=flush_with_concurrent_duplicate), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.post("/api/auth/register", json=self.payload)
            self.assertEqual(response.status_code, 409)
            rollback.assert_called_once()
        with self.session_factory() as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(GiaoVien)), 0)

    def test_unexpected_error_after_flush_rolls_back(self):
        original_rollback = Session.rollback
        with patch("app.services.auth_service.TeacherResponseSchema.model_validate", side_effect=RuntimeError("simulated response failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            with self.assertRaises(RuntimeError):
                self.client.post("/api/auth/register", json=self.payload)
            rollback.assert_called_once()
        with self.session_factory() as db:
            self.assertEqual(db.scalar(select(func.count()).select_from(GiaoVien)), 0)

    def profile_headers(self):
        teacher = self.register()
        return teacher, {"Authorization": "Bearer " + self.login()["access_token"]}

    def test_profile_update_persists_without_changing_credentials_or_other_teacher(self):
        teacher, headers = self.profile_headers()
        with self.session_factory() as db:
            original_hash = db.get(GiaoVien, teacher["id_gv"]).mat_khau
            other = GiaoVien(email="other@example.com", mat_khau=original_hash, ho_ten="Other teacher")
            db.add(other)
            db.commit()
            other_id = other.id_gv
        payload = {"ho_ten": "  Updated teacher  ", "truong": "  New school  ", "tinh": "  New city  "}
        response = self.client.put("/api/auth/profile", json=payload, headers=headers)
        self.assertEqual(response.status_code, 200, response.text)
        expected = {**teacher, "ho_ten": "Updated teacher", "truong": "New school", "tinh": "New city"}
        self.assertEqual(response.json(), expected)
        self.assertNotIn("mat_khau", response.json())
        self.assertEqual(self.client.get("/api/auth/me", headers=headers).json(), expected)
        with self.session_factory() as db:
            stored = db.get(GiaoVien, teacher["id_gv"])
            self.assertEqual((stored.ho_ten, stored.truong, stored.tinh), ("Updated teacher", "New school", "New city"))
            self.assertEqual(stored.mat_khau, original_hash)
            self.assertEqual(stored.email, teacher["email"])
            self.assertEqual(db.get(GiaoVien, other_id).ho_ten, "Other teacher")
        self.assertEqual(self.login()["token_type"], "bearer")

    def test_profile_optional_fields_can_be_cleared_or_omitted(self):
        _, headers = self.profile_headers()
        for payload in [
            {"ho_ten": "Teacher", "truong": None, "tinh": None},
            {"ho_ten": "Teacher", "truong": "  ", "tinh": ""},
            {"ho_ten": "Teacher"},
        ]:
            with self.subTest(payload=payload):
                response = self.client.put("/api/auth/profile", json=payload, headers=headers)
                self.assertEqual(response.status_code, 200, response.text)
                self.assertIsNone(response.json()["truong"])
                self.assertIsNone(response.json()["tinh"])

    def test_profile_requires_valid_teacher_jwt(self):
        teacher, _ = self.profile_headers()
        for headers in [
            {}, {"Authorization": "Basic abc"}, {"Authorization": "Bearer broken"},
            {"Authorization": "Bearer " + create_access_token({"sub": "unknown-teacher"})},
            {"Authorization": "Bearer " + create_access_token({"sub": teacher["id_gv"]}, timedelta(seconds=-60))},
        ]:
            with self.subTest(headers_present=bool(headers)):
                response = self.client.put("/api/auth/profile", json={"ho_ten": "Unauthorized"}, headers=headers)
                self.assertEqual(response.status_code, 401)
        with self.session_factory() as db:
            self.assertEqual(db.get(GiaoVien, teacher["id_gv"]).ho_ten, teacher["ho_ten"])

    def test_invalid_profile_inputs_do_not_commit(self):
        teacher, headers = self.profile_headers()
        invalid = [
            {}, {"ho_ten": "  "}, {"ho_ten": None}, {"ho_ten": 123},
            {"ho_ten": "Teacher", "truong": 123}, {"ho_ten": "Teacher", "tinh": []},
            {"ho_ten": "Teacher", "email": "hijack@example.com"},
            {"ho_ten": "Teacher", "id_gv": "another-teacher"},
            {"ho_ten": "Teacher", "mat_khau": "NewPassword123!"},
        ]
        with patch.object(Session, "commit", side_effect=AssertionError("Invalid profile committed")):
            for payload in invalid:
                with self.subTest(payload=payload):
                    self.assertEqual(self.client.put("/api/auth/profile", json=payload, headers=headers).status_code, 422)
        with self.session_factory() as db:
            self.assertEqual(db.get(GiaoVien, teacher["id_gv"]).ho_ten, teacher["ho_ten"])

    def test_profile_commit_failure_rolls_back_flushed_update(self):
        teacher, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("simulated commit failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/profile", json={"ho_ten": "Not committed", "truong": "New school"}, headers=headers)
            self.assertEqual(response.status_code, 503)
            rollback.assert_called_once()
        self.assertEqual(self.client.get("/api/auth/me", headers=headers).json(), teacher)

    def test_profile_response_failure_rolls_back_before_commit(self):
        teacher, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch("app.services.auth_service.TeacherResponseSchema.model_validate", side_effect=RuntimeError("simulated response failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            with self.assertRaises(RuntimeError):
                self.client.put("/api/auth/profile", json={"ho_ten": "Not committed"}, headers=headers)
            rollback.assert_called_once()
        self.assertEqual(self.client.get("/api/auth/me", headers=headers).json(), teacher)

    def test_profile_query_failure_rolls_back(self):
        teacher, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch.object(Session, "scalar", side_effect=SQLAlchemyError("simulated query failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/profile", json={"ho_ten": "Not committed"}, headers=headers)
            self.assertEqual(response.status_code, 503)
            rollback.assert_called_once()
        self.assertEqual(self.client.get("/api/auth/me", headers=headers).json(), teacher)

    def test_profile_missing_locked_row_returns_404_and_rolls_back(self):
        teacher, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch.object(Session, "scalar", return_value=None), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/profile", json={"ho_ten": "Not committed"}, headers=headers)
            self.assertEqual(response.status_code, 404)
            rollback.assert_called_once()
        self.assertEqual(self.client.get("/api/auth/me", headers=headers).json(), teacher)

    def test_profile_openapi_contract(self):
        spec = self.client.get("/openapi.json").json()
        operation = spec["paths"]["/api/auth/profile"]["put"]
        self.assertEqual(operation["security"], [{"HTTPBearer": []}])
        schema = spec["components"]["schemas"]["TeacherUpdateProfileSchema"]
        self.assertEqual(schema["required"], ["ho_ten"])
        self.assertEqual(set(schema["properties"]), {"ho_ten", "truong", "tinh"})
        self.assertFalse(schema["additionalProperties"])

    def test_change_password_stores_bcrypt_and_accepts_only_new_password_on_login(self):
        teacher, headers = self.profile_headers()
        new_password = "NewTeacherPass456!"
        response = self.client.put("/api/auth/change-password", headers=headers, json={
            "mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": new_password,
        })
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), {"status": "ok", "message": "Đã cập nhật mật khẩu."})
        self.assertNotIn(new_password, response.text)
        with self.session_factory() as db:
            stored = db.get(GiaoVien, teacher["id_gv"])
            self.assertTrue(stored.mat_khau.startswith("$2b$"))
            self.assertTrue(verify_password(new_password, stored.mat_khau))
            self.assertFalse(verify_password(self.payload["mat_khau"], stored.mat_khau))
            self.assertEqual(stored.email, teacher["email"])
            self.assertEqual(stored.ho_ten, teacher["ho_ten"])
        for password, expected_status in [(self.payload["mat_khau"], 401), (new_password, 200)]:
            login = self.client.post("/api/auth/login", json={"email": self.payload["email"], "mat_khau": password})
            self.assertEqual(login.status_code, expected_status)
        # Password changes do not alter the existing JWT contract.
        self.assertEqual(self.client.get("/api/auth/me", headers=headers).status_code, 200)

    def test_change_password_wrong_current_password_returns_precise_error_and_rolls_back(self):
        teacher, headers = self.profile_headers()
        original_rollback = Session.rollback
        with self.session_factory() as db:
            original_hash = db.get(GiaoVien, teacher["id_gv"]).mat_khau
        with patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/change-password", headers=headers, json={
                "mat_khau_cu": "WrongCurrent123!", "mat_khau_moi": "NewTeacherPass456!",
            })
            self.assertEqual(response.status_code, 400)
            self.assertEqual(response.json()["detail"], "Mật khẩu hiện tại không chính xác.")
            rollback.assert_called_once()
        with self.session_factory() as db:
            self.assertEqual(db.get(GiaoVien, teacher["id_gv"]).mat_khau, original_hash)

    def test_change_password_invalid_inputs_cannot_commit(self):
        _, headers = self.profile_headers()
        valid = {"mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": "NewTeacherPass456!"}
        invalid = [{}, {"mat_khau_cu": self.payload["mat_khau"]}, {**valid, "id_gv": "someone-else"}, {**valid, "email": "other@example.com"}]
        for field in ["mat_khau_cu", "mat_khau_moi"]:
            invalid.extend({**valid, field: value} for value in [None, 123, "short", "x" * 73, "é" * 37, "password\x00"])
        with patch.object(Session, "commit", side_effect=AssertionError("Invalid password committed")):
            for index, payload in enumerate(invalid):
                with self.subTest(case=index):
                    self.assertEqual(self.client.put("/api/auth/change-password", headers=headers, json=payload).status_code, 422)
        self.assertEqual(self.login()["token_type"], "bearer")

    def test_change_password_requires_teacher_jwt(self):
        teacher, _ = self.profile_headers()
        for headers in [{}, {"Authorization": "Bearer broken"}, {"Authorization": "Bearer " + create_access_token({"sub": "someone-else"})}]:
            response = self.client.put("/api/auth/change-password", headers=headers, json={
                "mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": "NewTeacherPass456!",
            })
            self.assertEqual(response.status_code, 401)
        self.assertEqual(self.login()["token_type"], "bearer")

    def test_change_password_commit_failure_rolls_back_hash(self):
        teacher, headers = self.profile_headers()
        with self.session_factory() as db:
            original_hash = db.get(GiaoVien, teacher["id_gv"]).mat_khau
        original_rollback = Session.rollback
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("simulated commit failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/change-password", headers=headers, json={
                "mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": "NewTeacherPass456!",
            })
            self.assertEqual(response.status_code, 503)
            rollback.assert_called_once()
        with self.session_factory() as db:
            self.assertEqual(db.get(GiaoVien, teacher["id_gv"]).mat_khau, original_hash)
        self.assertEqual(self.login()["token_type"], "bearer")

    def test_change_password_hashing_failure_rolls_back(self):
        _, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch("app.services.auth_service.hash_password", side_effect=RuntimeError("simulated hash failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            with self.assertRaises(RuntimeError):
                self.client.put("/api/auth/change-password", headers=headers, json={
                    "mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": "NewTeacherPass456!",
                })
            rollback.assert_called_once()
        self.assertEqual(self.login()["token_type"], "bearer")

    def test_change_password_query_failure_rolls_back(self):
        _, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch.object(Session, "scalar", side_effect=SQLAlchemyError("simulated query failure")), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/change-password", headers=headers, json={
                "mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": "NewTeacherPass456!",
            })
            self.assertEqual(response.status_code, 503)
            rollback.assert_called_once()

    def test_change_password_missing_locked_teacher_rolls_back(self):
        _, headers = self.profile_headers()
        original_rollback = Session.rollback
        with patch.object(Session, "scalar", return_value=None), patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            response = self.client.put("/api/auth/change-password", headers=headers, json={
                "mat_khau_cu": self.payload["mat_khau"], "mat_khau_moi": "NewTeacherPass456!",
            })
            self.assertEqual(response.status_code, 404)
            rollback.assert_called_once()

    def test_change_password_schema_hides_values_and_requires_both_fields(self):
        from app.schemas.auth import ChangePasswordSchema
        payload = ChangePasswordSchema(mat_khau_cu="CurrentPass123!", mat_khau_moi="NewTeacherPass456!")
        self.assertNotIn("CurrentPass123!", repr(payload))
        self.assertNotIn("NewTeacherPass456!", repr(payload))
        spec = self.client.get("/openapi.json").json()
        self.assertEqual(spec["paths"]["/api/auth/change-password"]["put"]["security"], [{"HTTPBearer": []}])
        schema = spec["components"]["schemas"]["ChangePasswordSchema"]
        self.assertEqual(set(schema["required"]), {"mat_khau_cu", "mat_khau_moi"})
        self.assertEqual(set(schema["properties"]), {"mat_khau_cu", "mat_khau_moi"})
        self.assertFalse(schema["additionalProperties"])

    def test_password_hash_helpers_reject_invalid_hash_and_long_password(self):
        self.assertFalse(verify_password("TeacherPass123!", "plain-text-or-invalid-hash"))
        with self.assertRaises(ValueError):
            hash_password("x" * 73)

    def test_health_and_openapi_response_exclude_password(self):
        self.assertEqual(self.client.get("/api/health").json(), {"status": "ok", "app": "Quiz AI & Exam System"})
        spec = self.client.get("/openapi.json").json()
        self.assertEqual(set(spec["components"]["schemas"]["TeacherResponseSchema"]["properties"]), {"id_gv", "email", "ho_ten", "truong", "tinh"})
        self.assertEqual(spec["components"]["securitySchemes"]["HTTPBearer"]["scheme"], "bearer")


if __name__ == "__main__":
    unittest.main()
