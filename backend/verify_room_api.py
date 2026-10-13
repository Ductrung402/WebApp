"""Live Task 3.1 HTTP/ORM proof on PostgreSQL; all fixture writes are rolled back."""

import json
from pathlib import Path
import secrets
import sys
import uuid
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.database import engine, get_db
from app.models.models import DeThi, GiaoVien, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam
from app.services import room_service
from main import fastapi_app as app


def expect_status(response, status):
    if response.status_code != status:
        raise RuntimeError(f"Expected HTTP {status}, got {response.status_code}: {response.text}")
    return response.json()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    docs = Path(__file__).resolve().parent.parent / "docs"
    emails, room_ids, student_ids, result_ids = [], [], [], []
    with engine.connect() as connection:
        transaction = connection.begin()
        def test_db():
            with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                yield db
        app.dependency_overrides[get_db] = test_db
        try:
            with TestClient(app) as client:
                def teacher_headers():
                    email = "task31-" + uuid.uuid4().hex + "@example.com"
                    password = secrets.token_urlsafe(18)
                    emails.append(email)
                    expect_status(client.post("/api/auth/register", json={
                        "email": email, "mat_khau": password, "ho_ten": "Task 3.1 Verification",
                    }), 201)
                    token = expect_status(client.post("/api/auth/login", json={"email": email, "mat_khau": password}), 200)
                    return {"Authorization": "Bearer " + token["access_token"]}
                headers = teacher_headers()
                other_headers = teacher_headers()
                exam = expect_status(client.post("/api/exams", headers=headers, json={
                    "tenDeThi": "Kiến thức về nước", "khoi": "6", "cauHoiList": [{
                        "noiDung": "Công thức hóa học của nước là gì?", "dapAnA": "H2O",
                        "dapAnB": "CO2", "dapAnC": "O2", "dapAnD": "NaCl", "dapAnDung": "A",
                    }],
                }), 201)
                payload = {"idDeThi": exam["idDeThi"], "tenLop": "6A", "thoiGianLamBai": 30}
                created = expect_status(client.post("/api/rooms", headers=headers, json=payload), 201)
                room_ids.append(created["idPhongThi"])
                assert created["trangThaiPhong"] == "DANG_CHO"
                assert len(created["maCodePhongThi"]) == 6
                assert all(char in room_service.CODE_ALPHABET for char in created["maCodePhongThi"])
                expect_status(client.post("/api/rooms", headers=other_headers, json=payload), 404)
                expect_status(client.get("/api/rooms/" + room_ids[0], headers=other_headers), 404)
                joined = expect_status(client.post("/api/rooms/join", json={
                    "hoVaTen": "Nguyễn Văn An", "maCodePhongThi": created["maCodePhongThi"],
                }), 201)
                student_ids.append(joined["idHs"])
                detail = expect_status(client.get("/api/rooms/" + room_ids[0], headers=headers), 200)
                assert len(detail["hocSinhList"]) == 1
                assert detail["hocSinhList"][0]["idHs"] == joined["idHs"]
                with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                    result = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == joined["idHs"]))
                    assert result.id_phong_thi == room_ids[0]
                    assert result.trang_thai == TrangThaiBaiLam.DANG_THI and result.diem_so == 0.0
                    result_ids.append(result.id_kq)
                # Exercise PostgreSQL's actual UNIQUE constraint by bypassing the pre-check
                # for the first candidate, then use the regular generator on the retry.
                normal_generator = room_service.generate_room_code
                candidates = iter([created["maCodePhongThi"]])
                def collide_then_generate(db):
                    return next(candidates, None) or normal_generator(db)
                with patch.object(room_service, "generate_room_code", side_effect=collide_then_generate) as generate:
                    second = expect_status(client.post("/api/rooms", headers=headers, json=payload), 201)
                    assert generate.call_count == 2
                room_ids.append(second["idPhongThi"])
                assert second["maCodePhongThi"] != created["maCodePhongThi"]
                # Prove that even a failure after both real PostgreSQL INSERTs
                # removes the entire admission, while preserving the room.
                failed_ids = []
                def fail_after_result_insert(mapper, write_connection, target):
                    assert write_connection.scalar(select(HocSinh.id_hs).where(HocSinh.id_hs == target.id_hs))
                    assert write_connection.scalar(select(KetQuaThi.id_kq).where(KetQuaThi.id_kq == target.id_kq))
                    failed_ids.append((target.id_hs, target.id_kq))
                    raise SQLAlchemyError("Verification: failure after both PostgreSQL INSERTs")
                event.listen(KetQuaThi, "after_insert", fail_after_result_insert)
                try:
                    expect_status(client.post("/api/rooms/join", json={
                        "hoVaTen": "Rollback verification", "maCodePhongThi": created["maCodePhongThi"],
                    }), 503)
                finally:
                    event.remove(KetQuaThi, "after_insert", fail_after_result_insert)
                assert len(failed_ids) == 1
                with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                    assert db.get(HocSinh, failed_ids[0][0]) is None
                    assert db.get(KetQuaThi, failed_ids[0][1]) is None
                    assert db.get(PhongThi, room_ids[0]) is not None
        finally:
            app.dependency_overrides.clear()
            transaction.rollback()
    with Session(engine) as db:
        assert db.scalar(select(GiaoVien).where(GiaoVien.email.in_(emails))) is None
        assert db.get(DeThi, exam["idDeThi"]) is None
        assert all(db.get(PhongThi, room_id) is None for room_id in room_ids)
        assert all(db.get(HocSinh, student_id) is None for student_id in student_ids)
        assert all(db.get(KetQuaThi, result_id) is None for result_id in result_ids)
    for filename, response in [
        ("room_create_response.json", created), ("student_join_response.json", joined),
        ("room_detail_response.json", detail),
    ]:
        (docs / filename).write_text(json.dumps(response, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(created, ensure_ascii=False, indent=2))
    print(json.dumps(joined, ensure_ascii=False, indent=2))
    print("PASS: PostgreSQL room=201/DANG_CHO, anonymous join=201, result=DANG_THI/0.0, roster=200, ownership=404, actual UNIQUE collision retry=201, failure after both INSERTs=503/full rollback; all test records rolled back.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
