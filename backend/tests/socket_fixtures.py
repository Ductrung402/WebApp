"""Shared database/socket fixtures without inherited test cases."""
import os
import tempfile
from unittest.mock import AsyncMock, Mock, patch
os.environ["JWT_SECRET_KEY"] = "test-only-key-never-use-outside-tests-123456789"
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from app.core.config import get_auth_settings
from app.core.security import create_access_token, hash_password
from app.core.socket_manager import ExamSocketManager
from app.models.models import Base, DeThi, GiaoVien, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam
from app.services import socket_service
from app.services import exam_flow_service
from app.core.exam_runtime import ExamRuntimeStore
FIXTURE_HASH = hash_password("SocketTestPass123!")

class SocketFixtures:
    def setUp(self):
        get_auth_settings.cache_clear()
        self.runtime_directory = tempfile.TemporaryDirectory(prefix="socket-test-runtime-")
        self.shared_runtime_patch = patch.object(exam_flow_service, "runtime_store", ExamRuntimeStore(self.runtime_directory.name))
        self.shared_runtime_patch.start()
        self.engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
        @event.listens_for(self.engine, "connect")
        def foreign_keys(connection, record):
            connection.execute("PRAGMA foreign_keys=ON")
        Base.metadata.create_all(self.engine)
        self.factory = sessionmaker(bind=self.engine)
        with self.factory() as db:
            for teacher_id, code in [("owner", "AB12CD"), ("other", "QR34ST")]:
                db.add(GiaoVien(id_gv=teacher_id, email=teacher_id + "@example.com", mat_khau=FIXTURE_HASH, ho_ten=teacher_id))
                db.flush()
                db.add(DeThi(id_de_thi=teacher_id + "-exam", id_gv=teacher_id, ten_de_thi="Fixture", kieu_tai_lieu="TEXT"))
                db.flush()
                db.add(PhongThi(id_phong_thi=teacher_id + "-room", id_gv=teacher_id, id_de_thi=teacher_id + "-exam", ma_code_phong_thi=code, ten_lop="6A", thoi_gian_lam_bai=30))
                db.flush()
                db.add(HocSinh(id_hs=teacher_id + "-student", ho_va_ten=teacher_id, ma_code_phong_thi=code))
                db.flush()
                db.add(KetQuaThi(id_hs=teacher_id + "-student", id_phong_thi=teacher_id + "-room", diem_so=0.0, trang_thai=TrangThaiBaiLam.DANG_THI))
            db.commit()
        self.database_patch = patch.object(socket_service, "SessionLocal", self.factory)
        self.database_patch.start()
        self.sessions = {}
        self.server = Mock()
        async def save_session(sid, data):
            self.sessions[sid] = data
        async def get_session(sid):
            return self.sessions[sid]
        self.server.save_session = AsyncMock(side_effect=save_session)
        self.server.get_session = AsyncMock(side_effect=get_session)
        for method in ["enter_room", "leave_room", "emit", "shutdown"]:
            setattr(self.server, method, AsyncMock())
        self.server.manager.is_connected.return_value = True
        self.manager = ExamSocketManager(self.server)
        self.student = {"maCodePhongThi": "AB12CD", "userType": "student", "idHs": "owner-student"}

    def tearDown(self):
        self.shared_runtime_patch.stop()
        self.runtime_directory.cleanup()
        self.database_patch.stop()
        self.engine.dispose()

    async def connect(self, sid="student-sid", teacher=None):
        await self.manager.connect(sid, {}, {"token": create_access_token({"sub": teacher})} if teacher else {})

