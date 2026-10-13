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
