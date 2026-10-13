"""Live PostgreSQL + Gemini API proof, with all test records rolled back."""

import json
from pathlib import Path
import secrets
import sys
import uuid

from fastapi.testclient import TestClient
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from main import fastapi_app as app
from app.core.database import engine, get_db
from app.models.models import CauHoi, DeThi, GiaoVien


def expect_status(response, status):
    if response.status_code != status:
        raise RuntimeError(f"Expected HTTP {status}, got {response.status_code}: {response.text}")
    return response.json()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    docs = Path(__file__).resolve().parent.parent / "docs"
    payload = json.loads((docs / "exam_generate_ai_request.json").read_text(encoding="utf-8"))
    test_emails = []
    with engine.connect() as connection:
        transaction = connection.begin()
        def test_db():
            with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                yield db
        app.dependency_overrides[get_db] = test_db
        try:
            with TestClient(app) as client:
                def teacher_headers():
                    email = "task22-" + uuid.uuid4().hex + "@example.com"
                    password = secrets.token_urlsafe(18)
                    test_emails.append(email)
                    expect_status(client.post("/api/auth/register", json={
                        "email": email, "mat_khau": password, "ho_ten": "Task 2.2 Verification",
                    }), 201)
                    token = expect_status(client.post("/api/auth/login", json={"email": email, "mat_khau": password}), 200)
                    return {"Authorization": "Bearer " + token["access_token"]}
                headers = teacher_headers()
                other_headers = teacher_headers()
                generated = expect_status(client.post("/api/exams/generate-ai", headers=headers, json=payload), 201)
                exam_id = generated["idDeThi"]
                old_question_ids = [question["idCauHoi"] for question in generated["cauHoiList"]]
                assert generated["soLuongCauHoi"] == payload["numQuestions"]
                with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                    assert db.get(DeThi, exam_id) is not None
                    assert db.scalar(select(func.count()).select_from(CauHoi).where(CauHoi.id_de_thi == exam_id)) == 2
                assert expect_status(client.get("/api/exams/" + exam_id, headers=headers), 200) == generated
                assert any(exam["idDeThi"] == exam_id for exam in expect_status(client.get("/api/exams", headers=headers), 200))
                expect_status(client.get("/api/exams/" + exam_id, headers=other_headers), 404)
                expect_status(client.delete("/api/exams/" + exam_id, headers=other_headers), 404)
                question_fields = {"noiDung", "dapAnA", "dapAnB", "dapAnC", "dapAnD", "dapAnDung"}
                update_payload = {
                    "tenDeThi": "Đề đã chỉnh sửa", "khoi": "6",
                    "cauHoiList": [{key: value for key, value in generated["cauHoiList"][0].items() if key in question_fields}],
                }
                expect_status(client.put("/api/exams/" + exam_id, headers=other_headers, json=update_payload), 404)
                updated = expect_status(client.put("/api/exams/" + exam_id, headers=headers, json=update_payload), 200)
                assert updated["soLuongCauHoi"] == 1
                with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                    assert all(db.get(CauHoi, question_id) is None for question_id in old_question_ids)
                expect_status(client.delete("/api/exams/" + exam_id, headers=headers), 200)
                with Session(bind=connection, join_transaction_mode="create_savepoint") as db:
                    assert db.get(DeThi, exam_id) is None
                    assert db.scalar(select(func.count()).select_from(CauHoi).where(CauHoi.id_de_thi == exam_id)) == 0
        finally:
            app.dependency_overrides.clear()
            transaction.rollback()
    with Session(engine) as db:
        assert db.scalar(select(GiaoVien).where(GiaoVien.email.in_(test_emails))) is None
        assert db.get(DeThi, exam_id) is None
    output = json.dumps(generated, ensure_ascii=False, indent=2)
    (docs / "exam_generate_ai_response.json").write_text(output + "\n", encoding="utf-8")
    print(output)
    print("PASS: PostgreSQL/Gemini generation=201, list/detail=200, ownership=404, update=200, delete/cascade=200; all test records rolled back.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
