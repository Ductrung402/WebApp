"""Room API validation, ownership, code collisions and real ORM rollback tests."""

from datetime import date
import json
import os
import unittest
from unittest.mock import patch

os.environ["JWT_SECRET_KEY"] = "test-only-key-never-use-outside-tests-123456789"

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, func, select
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.config import get_auth_settings
from app.core.database import get_db
from app.core.security import create_access_token, hash_password
from app.models.models import (
    Base, CauHoi, DeThi, GiaoVien, HocSinh, KetQuaThi, PhongThi,
    TrangThaiBaiLam, TrangThaiPhongThi,
)
from app.schemas.room import RoomResponseSchema, StudentResponseSchema
from app.services import room_service
from main import fastapi_app as app

FIXTURE_PASSWORD_HASH = hash_password("RoomTestPass123!")


class RoomApiTests(unittest.TestCase):
    def setUp(self):
        get_auth_settings.cache_clear()
        self.engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
        @event.listens_for(self.engine, "connect")
        def enable_foreign_keys(connection, record):
            connection.execute("PRAGMA foreign_keys=ON")
        Base.metadata.create_all(self.engine)
        self.factory = sessionmaker(bind=self.engine)
        with self.factory() as db:
            for teacher_id in ["owner", "other"]:
                teacher = GiaoVien(
                    id_gv=teacher_id, email=teacher_id + "@example.com",
                    mat_khau=FIXTURE_PASSWORD_HASH, ho_ten=teacher_id,
                )
                teacher.de_thi_list = [DeThi(
                    id_de_thi=teacher_id + "-exam", ten_de_thi="Exam", kieu_tai_lieu="TEXT",
                    so_luong_cau_hoi=1, cau_hoi_list=[CauHoi(
                        noi_dung="Water formula?", dap_an_a="H2O", dap_an_b="CO2",
                        dap_an_c="O2", dap_an_d="NaCl", dap_an_dung="A",
                    )],
                )]
                db.add(teacher)
            db.commit()
        def override_db():
            with self.factory() as db:
                yield db
        app.dependency_overrides[get_db] = override_db
        self.client = TestClient(app)
        self.client.__enter__()
        self.headers = {"Authorization": "Bearer " + create_access_token({"sub": "owner"})}
        self.other_headers = {"Authorization": "Bearer " + create_access_token({"sub": "other"})}
        self.payload = {"idDeThi": "owner-exam", "tenLop": "6A", "thoiGianLamBai": 30}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        app.dependency_overrides.clear()
        self.engine.dispose()

    def create_room(self, code=None):
        if code:
            with patch.object(room_service, "generate_room_code", return_value=code):
                response = self.client.post("/api/rooms", headers=self.headers, json=self.payload)
        else:
            response = self.client.post("/api/rooms", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 201, response.text)
        return response.json()

    def join(self, room, name="Nguyễn Văn An"):
        return self.client.post("/api/rooms/join", json={"hoVaTen": name, "maCodePhongThi": room["maCodePhongThi"]})

    def counts(self):
        with self.factory() as db:
            return tuple(db.scalar(select(func.count()).select_from(model)) for model in [PhongThi, HocSinh, KetQuaThi])

    def test_create_room_initial_state_code_and_empty_roster(self):
        room = self.create_room()
        self.assertRegex(room["maCodePhongThi"], r"^[A-Z0-9]{6}$")
        self.assertEqual(room["trangThaiPhong"], "DANG_CHO")
        self.assertEqual(room["thoiGianLamBai"], 30)
        self.assertEqual(room["ngayTaoPhong"], date.today().isoformat())
        response = self.client.get("/api/rooms/" + room["idPhongThi"], headers=self.headers)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), {**room, "hocSinhList": [], "ketQuaList": []})
        with self.factory() as db:
            model = db.get(PhongThi, room["idPhongThi"])
            self.assertEqual(model.id_gv, "owner")
            self.assertEqual(model.id_de_thi, "owner-exam")

    def test_join_is_anonymous_and_commits_student_result_once(self):
        room = self.create_room()
        original_commit = Session.commit
        with patch.object(Session, "commit", autospec=True, side_effect=original_commit) as commit:
            response = self.join(room, "  Nguyễn Văn An  ")
            self.assertEqual(response.status_code, 201, response.text)
            commit.assert_called_once()
        joined = response.json()
        self.assertEqual(joined["hoVaTen"], "Nguyễn Văn An")
        self.assertEqual(joined["idPhongThi"], room["idPhongThi"])
        self.assertEqual(set(joined), {"idHs", "hoVaTen", "maCodePhongThi", "idPhongThi", "tenLop", "thoiGianLamBai"})
        self.assertEqual(self.counts(), (1, 1, 1))
        with self.factory() as db:
            student = db.get(HocSinh, joined["idHs"])
            result = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == student.id_hs))
            self.assertEqual(student.ma_code_phong_thi, room["maCodePhongThi"])
            self.assertEqual(result.id_phong_thi, room["idPhongThi"])
            self.assertEqual(result.trang_thai, TrangThaiBaiLam.DANG_THI)
            self.assertEqual(result.diem_so, 0.0)
            self.assertEqual(db.get(PhongThi, room["idPhongThi"]).trang_thai_phong, TrangThaiPhongThi.DANG_CHO)
        detail = self.client.get("/api/rooms/" + room["idPhongThi"], headers=self.headers).json()
        self.assertEqual(detail["hocSinhList"], [{key: joined[key] for key in ["idHs", "hoVaTen", "maCodePhongThi"]}])

    def test_same_name_can_join_more_than_once_without_accounts(self):
        room = self.create_room()
        first, second = self.join(room), self.join(room)
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 201)
        self.assertNotEqual(first.json()["idHs"], second.json()["idHs"])
        self.assertEqual(self.counts(), (1, 2, 2))

    def test_active_room_allows_admission_closed_room_rejects(self):
        room = self.create_room()
        with self.factory() as db:
            model = db.get(PhongThi, room["idPhongThi"])
            model.trang_thai_phong = TrangThaiPhongThi.DANG_THI
            db.commit()
        self.assertEqual(self.join(room).status_code, 201)
        with self.factory() as db:
            db.get(PhongThi, room["idPhongThi"]).trang_thai_phong = TrangThaiPhongThi.DA_DONG
            db.commit()
        self.assertEqual(self.join(room).status_code, 409)
        self.assertEqual(self.counts(), (1, 1, 1))

    def test_unknown_room_code_returns_404_without_inserts(self):
        response = self.client.post("/api/rooms/join", json={"hoVaTen": "An", "maCodePhongThi": "ABC123"})
        self.assertEqual(response.status_code, 404)
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_teacher_creation_and_roster_require_authentication(self):
        self.assertEqual(self.client.post("/api/rooms", json=self.payload).status_code, 401)
        self.assertEqual(self.client.get("/api/rooms/missing").status_code, 401)
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_teacher_cannot_create_from_unowned_or_missing_exam(self):
        for exam_id in ["other-exam", "missing"]:
            with self.subTest(exam_id=exam_id):
                response = self.client.post("/api/rooms", headers=self.headers, json={**self.payload, "idDeThi": exam_id})
                self.assertEqual(response.status_code, 404, response.text)
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_roster_is_visible_only_to_room_owner(self):
        room = self.create_room()
        self.join(room)
        self.assertEqual(self.client.get("/api/rooms/" + room["idPhongThi"], headers=self.other_headers).status_code, 404)
        self.assertEqual(self.client.get("/api/rooms/missing", headers=self.headers).status_code, 404)
        self.assertEqual(self.counts(), (1, 1, 1))

    def test_invalid_create_is_422_before_any_sql(self):
        invalid = [
            {}, {**self.payload, "idDeThi": " "}, {**self.payload, "tenLop": " "},
            {**self.payload, "idGv": "other"}, {**self.payload, "maCodePhongThi": "ABC123"},
            {**self.payload, "trangThaiPhong": "DANG_THI"},
        ] + [{**self.payload, "thoiGianLamBai": value} for value in [0, -1, 2.5, "30", True, None, 2_147_483_648]]
        with patch.object(Session, "_execute_internal", side_effect=AssertionError("Invalid payload queried DB")):
            for payload in invalid:
                with self.subTest(payload=payload):
                    self.assertEqual(self.client.post("/api/rooms", headers=self.headers, json=payload).status_code, 422)

    def test_invalid_join_is_422_before_any_sql(self):
        valid = {"hoVaTen": "An", "maCodePhongThi": "ABC123"}
        invalid = [{}, {**valid, "hoVaTen": " "}, {**valid, "hoVaTen": 12}, {**valid, "password": "irrelevant"}]
        invalid += [{**valid, "maCodePhongThi": code} for code in ["abc123", "ABCDE", "ABCDEFG", "ABC12!", "ABC123\n", None, 123456]]
        with patch.object(Session, "_execute_internal", side_effect=AssertionError("Invalid payload queried DB")):
            for payload in invalid:
                with self.subTest(payload=payload):
                    self.assertEqual(self.client.post("/api/rooms/join", json=payload).status_code, 422)

    def test_code_generator_skips_existing_code(self):
        self.create_room("ABC123")
        with patch.object(room_service.secrets, "choice", side_effect=list("ABC123XYZ789")):
            room = self.create_room()
        self.assertEqual(room["maCodePhongThi"], "XYZ789")
        self.assertEqual(self.counts(), (2, 0, 0))

    def test_db_unique_collision_rolls_back_then_retries_insert(self):
        self.create_room("ABC123")
        original_rollback = Session.rollback
        with patch.object(room_service, "generate_room_code", side_effect=["ABC123", "XYZ789"]) as generate, patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
            room = self.create_room()
            self.assertEqual(generate.call_count, 2)
            rollback.assert_called_once()
        self.assertEqual(room["maCodePhongThi"], "XYZ789")
        self.assertEqual(self.counts(), (2, 0, 0))

    def test_exhausted_insert_collisions_return_503_without_extra_rooms(self):
        self.create_room("ABC123")
        with patch.object(room_service, "generate_room_code", return_value="ABC123") as generate:
            response = self.client.post("/api/rooms", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 503, response.text)
        self.assertEqual(generate.call_count, room_service.MAX_INSERT_ATTEMPTS)
        self.assertEqual(self.counts(), (1, 0, 0))

    def test_exhausted_candidate_search_returns_503_without_extra_rooms(self):
        self.create_room("AAAAAA")
        with patch.object(room_service.secrets, "choice", return_value="A") as choice:
            response = self.client.post("/api/rooms", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(choice.call_count, 6 * room_service.MAX_CODE_ATTEMPTS)
        self.assertEqual(self.counts(), (1, 0, 0))

    def test_unrelated_integrity_error_is_not_retried(self):
        failure = IntegrityError("INSERT", {}, ValueError("foreign key constraint failure"))
        def fail_after_insert(mapper, connection, target):
            raise failure
        event.listen(PhongThi, "after_insert", fail_after_insert)
        try:
            with patch.object(room_service, "generate_room_code", return_value="ABC123") as generate:
                response = self.client.post("/api/rooms", headers=self.headers, json=self.payload)
        finally:
            event.remove(PhongThi, "after_insert", fail_after_insert)
        self.assertEqual(response.status_code, 409)
        generate.assert_called_once()
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_failure_after_result_insert_rolls_back_both_student_and_result(self):
        room = self.create_room()
        inserted = []
        def fail_after_insert(mapper, connection, target):
            inserted.append(tuple(connection.scalar(select(func.count()).select_from(model)) for model in [HocSinh, KetQuaThi]))
            raise SQLAlchemyError("Simulated result failure after both INSERTs")
        event.listen(KetQuaThi, "after_insert", fail_after_insert)
        original_rollback = Session.rollback
        try:
            with patch.object(Session, "rollback", autospec=True, side_effect=original_rollback) as rollback:
                response = self.join(room)
                rollback.assert_called_once()
            self.assertEqual(response.status_code, 503, response.text)
        finally:
            event.remove(KetQuaThi, "after_insert", fail_after_insert)
        self.assertEqual(inserted, [(1, 1)])
        self.assertEqual(self.counts(), (1, 0, 0))

    def test_failure_after_student_insert_leaves_no_orphan(self):
        room = self.create_room()
        def fail_after_insert(mapper, connection, target):
            raise SQLAlchemyError("Student INSERT failed")
        event.listen(HocSinh, "after_insert", fail_after_insert)
        try:
            self.assertEqual(self.join(room).status_code, 503)
        finally:
            event.remove(HocSinh, "after_insert", fail_after_insert)
        self.assertEqual(self.counts(), (1, 0, 0))

    def test_join_commit_failure_rolls_back_student_and_result(self):
        room = self.create_room()
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("Commit failure")):
            response = self.join(room)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.counts(), (1, 0, 0))

    def test_create_commit_failure_rolls_back_room(self):
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("Commit failure")):
            response = self.client.post("/api/rooms", headers=self.headers, json=self.payload)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_existing_room_cascade_removes_students_and_results(self):
        room = self.create_room()
        self.join(room)
        with self.factory() as db:
            db.delete(db.get(PhongThi, room["idPhongThi"]))
            db.commit()
        self.assertEqual(self.counts(), (0, 0, 0))

    def test_schema_types_and_nullable_columns_match_orm(self):
        room = self.create_room()
        parsed = RoomResponseSchema.model_validate_json(json.dumps(room))
        self.assertIsInstance(parsed.ngay_tao_phong, date)
        self.assertIsInstance(parsed.thoi_gian_lam_bai, int)
        self.assertIsInstance(parsed.trang_thai_phong, TrangThaiPhongThi)
        self.assertEqual(set(StudentResponseSchema.model_fields), set(HocSinh.__table__.columns.keys()))
        self.assertEqual(set(RoomResponseSchema.model_fields), set(PhongThi.__table__.columns.keys()) - {"id_gv", "id_de_thi"})
        with self.factory() as db:
            model = db.get(PhongThi, room["idPhongThi"])
            model.ngay_tao_phong = None
            model.trang_thai_phong = None
            db.commit()
        response = self.client.get("/api/rooms/" + room["idPhongThi"], headers=self.headers)
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["ngayTaoPhong"])
        self.assertIsNone(response.json()["trangThaiPhong"])
        self.assertEqual(self.join(room).status_code, 409)

    def test_snake_case_input_and_camel_case_output(self):
        response = self.client.post("/api/rooms", headers=self.headers, json={
            "id_de_thi": "owner-exam", "ten_lop": "6B", "thoi_gian_lam_bai": 45,
        })
        self.assertEqual(response.status_code, 201, response.text)
        room = response.json()
        response = self.client.post("/api/rooms/join", json={
            "ho_va_ten": "An", "ma_code_phong_thi": room["maCodePhongThi"],
        })
        self.assertEqual(response.status_code, 201, response.text)
        self.assertIn("hoVaTen", response.json())
        self.assertNotIn("ho_va_ten", response.json())


if __name__ == "__main__":
    unittest.main()
