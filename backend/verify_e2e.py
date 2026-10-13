"""Task 5.3: real Chrome + Gemini + REST/Socket.IO + isolated PostgreSQL schema.

Run from repository root: .venv/Scripts/python.exe -B backend/verify_e2e.py
Requires local PostgreSQL, configured backend/.env, frontend dependencies and Chrome.
No production tables or runtime sidecars are changed. One real AI request is made.
"""

import asyncio
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import uuid

from sqlalchemy import create_engine, select, text
from sqlalchemy.orm import sessionmaker
import httpx
import uvicorn

from app.core.database import engine as configured_engine, get_db
from app.core.exam_runtime import ExamRuntimeStore
from app.models.models import Base, CauHoi, DeThi, GiaoVien, HocSinh, KetQuaThi, PhongThi
from app.services import exam_flow_service, socket_service
from main import app, fastapi_app

ROOT = Path(__file__).resolve().parents[1]
PROOF = ROOT / "docs/qa"


async def verify(directory):
    schema = "qa_e2e_" + uuid.uuid4().hex
    assert schema.startswith("qa_e2e_") and schema.isidentifier()
    model_path = ROOT / "backend/app/models/models.py"
    model_hash = hashlib.sha256(model_path.read_bytes()).hexdigest()
    with configured_engine.begin() as conn:
        conn.execute(text(f'CREATE SCHEMA "{schema}"'))
    engine = create_engine(configured_engine.url, connect_args={"options": f"-csearch_path={schema}"})
    factory = sessionmaker(bind=engine)
    old_factory, old_runtime = socket_service.SessionLocal, exam_flow_service.runtime_store
    old_override = fastapi_app.dependency_overrides.get(get_db)
    server_task = server = listener = vite = browser_process = None
    try:
        Base.metadata.create_all(engine)
        socket_service.SessionLocal = factory
        exam_flow_service.runtime_store = ExamRuntimeStore(Path(directory) / "runtime")

        def test_db():
            with factory() as db:
                yield db

        fastapi_app.dependency_overrides[get_db] = test_db
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(128)
        port = listener.getsockname()[1]
        url = f"http://localhost:{port}"
        server = uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False))
        server_task = asyncio.create_task(server.serve(sockets=[listener]))
        for _ in range(200):
            if server.started:
                break
            if server_task.done():
                await server_task
            await asyncio.sleep(0.01)
        assert server.started, "QA backend did not start."
        # Never reuse a Vite process with a different backend URL.
        with socket.socket() as check:
            assert check.connect_ex(("localhost", 5173)) != 0, "Stop the existing Vite server on port 5173 before this test."
        environment = {**os.environ, "VITE_API_BASE_URL": url, "VITE_SOCKET_URL": url}
        vite_log = open(Path(directory) / "vite.log", "w", encoding="utf-8")
        vite = subprocess.Popen(
            ["cmd.exe", "/c", "npm.cmd", "run", "dev"], cwd=ROOT / "frontend",
            env=environment, stdout=vite_log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )
        async with httpx.AsyncClient(trust_env=False) as client:
            for _ in range(150):
                try:
                    if (await client.get("http://localhost:5173", timeout=1)).status_code == 200:
                        break
                except httpx.HTTPError:
                    pass
                if vite.poll() is not None:
                    raise RuntimeError("QA Vite server failed to start.")
                await asyncio.sleep(0.1)
            else:
                raise RuntimeError("QA Vite server startup timed out.")
        PROOF.mkdir(parents=True, exist_ok=True)
        browser_process = await asyncio.create_subprocess_exec(
            shutil.which("node"), str(ROOT / "frontend/scripts/verify-e2e.mjs"),
            cwd=ROOT / "frontend", stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(
            browser_process.communicate(json.dumps({"apiUrl": url}).encode()), timeout=180,
        )
        # The browser driver prints only step labels, never passwords/JWTs/keys.
        print(stdout.decode("utf-8", errors="replace"), end="", flush=True)
        if browser_process.returncode:
            raise RuntimeError(stderr.decode("utf-8", errors="replace"))
        proof = json.loads((PROOF / "e2e_results.json").read_text(encoding="utf-8"))
        with factory() as db:
            teacher = db.scalar(select(GiaoVien))
            exam = db.scalar(select(DeThi))
            room = db.scalar(select(PhongThi))
            student = db.scalar(select(HocSinh))
            grade = db.scalar(select(KetQuaThi))
            question_count = len(db.scalars(select(CauHoi)).all())
            assert teacher.mat_khau.startswith("$2") and teacher.mat_khau != "QaTeacherPass123!"
            assert exam.so_luong_cau_hoi == question_count == 3
            assert student.id_hs == grade.id_hs
            assert grade.trang_thai.value == "HOAN_THANH" and grade.diem_so == 6.67
            assert room.trang_thai_phong.value == "DA_DONG"
            proof["databaseVerification"] = {
                "engine": "PostgreSQL", "isolatedSchema": schema, "passwordHashed": True,
                "questionCount": question_count, "studentCount": 1,
                "diemSo": grade.diem_so, "trangThai": grade.trang_thai.value,
                "trangThaiPhong": room.trang_thai_phong.value,
            }
        assert hashlib.sha256(model_path.read_bytes()).hexdigest() == model_hash
        proof["modelsSha256"] = model_hash
        (PROOF / "e2e_results.json").write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    finally:
        if browser_process and browser_process.returncode is None:
            browser_process.kill()
            await browser_process.wait()
        if vite:
            # Kill only the process tree started above, including npm's Vite child.
            subprocess.run(["taskkill", "/PID", str(vite.pid), "/T", "/F"], capture_output=True)
            vite.wait(timeout=10)
            vite_log.close()
        if server:
            server.should_exit = True
            await server_task
        if listener:
            listener.close()
        socket_service.SessionLocal = old_factory
        exam_flow_service.runtime_store = old_runtime
        if old_override is None:
            fastapi_app.dependency_overrides.pop(get_db, None)
        else:
            fastapi_app.dependency_overrides[get_db] = old_override
        engine.dispose()
        # Exact schema created by this invocation; production/public is never targeted.
        with configured_engine.begin() as conn:
            conn.execute(text(f'DROP SCHEMA "{schema}" CASCADE'))
    print("PASS: PostgreSQL QA schema removed; production data and models unchanged.", flush=True)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="quiz-qa-") as directory:
        asyncio.run(verify(directory))
