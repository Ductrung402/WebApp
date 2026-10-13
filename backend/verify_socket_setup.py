"""Uvicorn + real JS Socket.IO clients against an isolated, temporary fixture DB."""

import asyncio
import io
import json
import logging
import os
from pathlib import Path
import shutil
import socket
import sys
import tempfile

os.environ["JWT_SECRET_KEY"] = "verification-only-key-never-use-in-production-123456789"

from sqlalchemy import create_engine, event, select
from sqlalchemy.orm import sessionmaker
import uvicorn

from app.core.security import create_access_token, hash_password
from app.models.models import Base, CauHoi, DeThi, GiaoVien, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.services import socket_service
from app.services import exam_flow_service
from app.core.exam_runtime import ExamRuntimeStore
from app.core.database import get_db
from main import app, fastapi_app


async def verify(directory, flow=False, recovery=False, grading=False, leaderboard=False):
    grading = grading or leaderboard
    workspace = Path(__file__).resolve().parent.parent
    engine = create_engine("sqlite:///" + str(Path(directory) / "socket_fixture.db"), connect_args={"check_same_thread": False})
    @event.listens_for(engine, "connect")
    def foreign_keys(connection, record):
        connection.execute("PRAGMA foreign_keys=ON")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine)
    password_hash = hash_password("SocketProofPass123!")
    with factory() as db:
        for teacher_id, code in [("fixture-teacher", "AB12CD"), ("fixture-other", "QR34ST")]:
            teacher = GiaoVien(id_gv=teacher_id, email=teacher_id + "@example.com", mat_khau=password_hash, ho_ten="Socket verification")
            questions = [CauHoi(noi_dung="Water formula?", dap_an_a="H2O", dap_an_b="CO2", dap_an_c="O2", dap_an_d="NaCl", dap_an_dung="A")]
            if grading:
                questions = [CauHoi(id_cau_hoi=f"{teacher_id}-q-{index}", noi_dung=f"Question {index}", dap_an_a="A option", dap_an_b="B option", dap_an_c="C option", dap_an_d="D option", dap_an_dung=correct) for index, correct in enumerate(["A", "B", "C"], 1)]
            teacher.de_thi_list = [DeThi(
                id_de_thi=teacher_id + "-exam", ten_de_thi="Socket fixture", kieu_tai_lieu="TEXT", so_luong_cau_hoi=1,
                cau_hoi_list=questions,
            )]
            db.add(teacher)
            db.flush()
            db.add(PhongThi(id_phong_thi=teacher_id + "-room", id_gv=teacher_id, id_de_thi=teacher_id + "-exam", ma_code_phong_thi=code, ten_lop="6A", thoi_gian_lam_bai=30))
            db.flush()
        db.add(HocSinh(id_hs="fixture-student", ho_va_ten="Nguyễn Văn An", ma_code_phong_thi="AB12CD"))
        db.flush()
        db.add(KetQuaThi(id_hs="fixture-student", id_phong_thi="fixture-teacher-room", trang_thai=TrangThaiBaiLam.DANG_THI, diem_so=0.0))
        db.commit()
    previous_factory = socket_service.SessionLocal
    socket_service.SessionLocal = factory
    previous_runtime = exam_flow_service.runtime_store
    exam_flow_service.runtime_store = ExamRuntimeStore(Path(directory) / "runtime")
    previous_override = fastapi_app.dependency_overrides.get(get_db)
    def override_db():
        with factory() as db:
            yield db
    fastapi_app.dependency_overrides[get_db] = override_db
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    logger = logging.getLogger("uvicorn.error.socket")
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(128)
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", access_log=False))
    # Uvicorn applies dictConfig during Config construction, so attach afterwards.
    logger.setLevel(logging.INFO)
    logger.addHandler(handler)
    task = asyncio.create_task(server.serve(sockets=[listener]))
    process = None
    try:
        for _ in range(200):
            if server.started:
                break
            if task.done():
                await task
                raise RuntimeError("Verification server stopped before startup.")
            await asyncio.sleep(0.01)
        if not server.started:
            raise RuntimeError("Verification server did not start.")
        fixture = {
            "url": f"http://127.0.0.1:{port}",
            "token": create_access_token({"sub": "fixture-teacher"}),
            "otherToken": create_access_token({"sub": "fixture-other"}),
        }
        node = shutil.which("node")
        if not node:
            raise RuntimeError("Node.js is required for the real socket.io-client proof.")
        process = await asyncio.create_subprocess_exec(
            node, str(workspace / "frontend/scripts" / ("verify-submission.mjs" if grading else "verify-exam-recovery.mjs" if recovery else "verify-exam-flow.mjs" if flow else "verify-socket.mjs")),
            *(["--leaderboard"] if leaderboard else []),
            cwd=str(workspace / "frontend"), stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(process.communicate(json.dumps(fixture).encode()), timeout=30)
        if process.returncode != 0:
            raise RuntimeError("Real JS socket verification failed: " + stderr.decode(errors="replace"))
        # Verify cleanup commits ERR_02 for unfinished work and preserves completed grades.
        for _ in range(100):
            with factory() as db:
                result = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == "fixture-student"))
                disconnected = result.trang_thai == (TrangThaiBaiLam.HOAN_THANH if flow or grading else TrangThaiBaiLam.MAT_KET_NOI)
                assert result.diem_so == (6.67 if grading else 0.0)
                assert db.get(PhongThi, "fixture-teacher-room").trang_thai_phong == (TrangThaiPhongThi.DA_DONG if leaderboard else TrangThaiPhongThi.DANG_THI if flow or recovery or grading else TrangThaiPhongThi.DANG_CHO)
            if disconnected:
                break
            await asyncio.sleep(0.01)
        assert disconnected, "Expected final student state was not committed."
        output = stdout.decode()
    finally:
        if process and process.returncode is None:
            process.kill()
            await process.wait()
        server.should_exit = True
        await task
        listener.close()
        logger.removeHandler(handler)
        socket_service.SessionLocal = previous_factory
        exam_flow_service.runtime_store = previous_runtime
        if previous_override is None:
            fastapi_app.dependency_overrides.pop(get_db, None)
        else:
            fastapi_app.dependency_overrides[get_db] = previous_override
        engine.dispose()
    proof = "SERVER LOG\n" + stream.getvalue() + "\nCLIENT LOG\n" + output
    (workspace / "docs" / ("leaderboard_proof.log" if leaderboard else "submission_proof.log" if grading else "exam_recovery_proof.log" if recovery else "exam_flow_proof.log" if flow else "socket_setup_proof.log")).write_text(proof, encoding="utf-8")
    if leaderboard:
        for prefix, name in [("CLOSE_OUTPUT ", "room_closed_response.json"), ("SCOREBOARD_OUTPUT ", "leaderboard_response.json")]:
            state_line = next(line.removeprefix(prefix) for line in output.splitlines() if line.startswith(prefix))
            (workspace / "docs" / name).write_text(json.dumps(json.loads(state_line), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif grading:
        for prefix, name in [("SUBMISSION_OUTPUT ", "submission_response.json"), ("TEACHER_OUTPUT ", "student_submitted_response.json")]:
            state_line = next(line.removeprefix(prefix) for line in output.splitlines() if line.startswith(prefix))
            (workspace / "docs" / name).write_text(json.dumps(json.loads(state_line), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif recovery:
        for prefix, name in [("DISCONNECT_OUTPUT ", "student_disconnected_response.json"), ("RECOVERY_OUTPUT ", "student_reconnect_response.json")]:
            state_line = next(line.removeprefix(prefix) for line in output.splitlines() if line.startswith(prefix))
            (workspace / "docs" / name).write_text(json.dumps(json.loads(state_line), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif flow:
        state_line = next(line.removeprefix("STATE_OUTPUT ") for line in output.splitlines() if line.startswith("STATE_OUTPUT "))
        (workspace / "docs/exam_started_response.json").write_text(json.dumps(json.loads(state_line), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(proof)
    print("PASS: isolated fixture database removed; production PostgreSQL was not modified.")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    with tempfile.TemporaryDirectory(prefix="task41-") as directory:
        asyncio.run(verify(directory, flow="--flow" in sys.argv, recovery="--recovery" in sys.argv, grading="--grading" in sys.argv, leaderboard="--leaderboard" in sys.argv))
