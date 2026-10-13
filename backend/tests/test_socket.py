"""Socket admission, room isolation, worker-thread SQL and membership cleanup."""

import asyncio
import os
import threading
import unittest
from unittest.mock import AsyncMock, Mock, patch

os.environ["JWT_SECRET_KEY"] = "test-only-key-never-use-outside-tests-123456789"

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.core.config import get_auth_settings
from app.core.security import create_access_token, hash_password
from app.core.socket_manager import ExamSocketManager
from app.models.models import Base, DeThi, GiaoVien, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.services import socket_service
from main import app

from socket_fixtures import SocketFixtures


class SocketTests(SocketFixtures, unittest.IsolatedAsyncioTestCase):
    async def test_student_joins_exact_code_and_broadcast_is_teacher_only(self):
        await self.connect()
        ack = await self.manager.join_exam_room("student-sid", self.student)
        self.assertEqual(ack["status"], "ok")
        self.assertEqual(ack["idHs"], "owner-student")
        self.server.enter_room.assert_awaited_once_with("student-sid", "AB12CD")
        args, kwargs = self.server.emit.call_args
        self.assertEqual(args[0], "room_students_updated")
        self.assertEqual(kwargs, {"room": "teachers:AB12CD"})
        self.assertEqual(args[1]["hocSinhList"], [{"idHs": "owner-student", "hoVaTen": "owner", "maCodePhongThi": "AB12CD"}])

    async def test_owner_teacher_joins_common_and_private_rooms(self):
        await self.connect("teacher-sid", "owner")
        ack = await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        self.assertEqual(ack["status"], "ok")
        self.assertEqual([call.args for call in self.server.enter_room.call_args_list], [("teacher-sid", "AB12CD"), ("teacher-sid", "teachers:AB12CD")])

    async def test_teacher_without_token_or_wrong_owner_is_denied(self):
        for sid, teacher, expected in [("guest", None, 401), ("other-teacher", "other", 403)]:
            await self.connect(sid, teacher)
            ack = await self.manager.join_exam_room(sid, {"maCodePhongThi": "AB12CD", "userType": "teacher"})
            self.assertEqual(ack["statusCode"], expected)
        self.server.enter_room.assert_not_awaited()

    async def test_forged_teacher_token_is_denied(self):
        await self.manager.connect("forged", {}, {"token": "not-a-jwt"})
        ack = await self.manager.join_exam_room("forged", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        self.assertEqual(ack["statusCode"], 401)
        self.server.emit.assert_not_awaited()

    async def test_student_from_another_room_or_unknown_student_is_denied(self):
        await self.connect()
        for student_id in ["other-student", "missing"]:
            ack = await self.manager.join_exam_room("student-sid", {**self.student, "idHs": student_id})
            self.assertEqual(ack["statusCode"], 403)
        self.server.enter_room.assert_not_awaited()

    async def test_invalid_packets_do_not_call_database_worker(self):
        await self.connect()
        invalid = [None, [], {}, {**self.student, "maCodePhongThi": "abc123"}, {**self.student, "idHs": None}, {**self.student, "userType": "admin"}, {**self.student, "secret": "extra"}, {**self.student, "idHs": 12}]
        with patch.object(socket_service, "authorize_socket_join", side_effect=AssertionError("Invalid payload queried SQL")) as authorize:
            for payload in invalid:
                ack = await self.manager.join_exam_room("student-sid", payload)
                self.assertEqual(ack["statusCode"], 422)
            authorize.assert_not_called()

    async def test_unknown_and_closed_rooms_are_denied(self):
        await self.connect()
        ack = await self.manager.join_exam_room("student-sid", {**self.student, "maCodePhongThi": "ZZ99ZZ"})
        self.assertEqual(ack["statusCode"], 404)
        with self.factory() as db:
            db.get(PhongThi, "owner-room").trang_thai_phong = TrangThaiPhongThi.DA_DONG
            db.commit()
        ack = await self.manager.join_exam_room("student-sid", self.student)
        self.assertEqual(ack["statusCode"], 409)

    async def test_sql_is_filtered_and_runs_outside_event_loop(self):
        statements = []
        main_thread = threading.get_ident()
        def capture(connection, cursor, statement, parameters, context, executemany):
            statements.append((statement.upper(), threading.get_ident()))
        event.listen(self.engine, "before_cursor_execute", capture)
        try:
            await self.connect()
            ack = await self.manager.join_exam_room("student-sid", self.student)
            self.assertEqual(ack["status"], "ok")
        finally:
            event.remove(self.engine, "before_cursor_execute", capture)
        self.assertEqual(len(statements), 2)
        self.assertTrue(all("WHERE" in sql and "LIMIT" in sql for sql, thread in statements))
        self.assertTrue(all(thread != main_thread for sql, thread in statements))

    async def test_database_error_returns_sanitized_503(self):
        await self.connect()
        def fail(connection, cursor, statement, parameters, context, executemany):
            raise SQLAlchemyError("private database details")
        event.listen(self.engine, "before_cursor_execute", fail)
        try:
            ack = await self.manager.join_exam_room("student-sid", self.student)
        finally:
            event.remove(self.engine, "before_cursor_execute", fail)
        self.assertEqual(ack["statusCode"], 503)
        self.assertNotIn("private", str(ack))

    async def test_duplicate_join_and_multiple_tabs_are_deduplicated(self):
        await self.connect("one")
        await self.connect("two")
        await self.manager.join_exam_room("one", self.student)
        await self.manager.join_exam_room("one", self.student)
        await self.manager.join_exam_room("two", self.student)
        self.assertEqual(len(self.server.emit.call_args.args[1]["hocSinhList"]), 1)
        await self.manager.disconnect("one")
        self.assertEqual(len(self.server.emit.call_args.args[1]["hocSinhList"]), 1)
        await self.manager.disconnect("two")
        self.assertEqual(self.server.emit.call_args.args[1]["hocSinhList"], [])
        self.assertEqual(self.manager.students, {})

    async def test_room_rosters_are_isolated(self):
        await self.connect("one")
        await self.connect("two")
        await self.manager.join_exam_room("one", self.student)
        await self.manager.join_exam_room("two", {"maCodePhongThi": "QR34ST", "userType": "student", "idHs": "other-student"})
        args, kwargs = self.server.emit.call_args
        self.assertEqual(kwargs["room"], "teachers:QR34ST")
        self.assertEqual([student["idHs"] for student in args[1]["hocSinhList"]], ["other-student"])

    async def test_disconnect_updates_result_without_changing_score(self):
        await self.connect()
        await self.manager.join_exam_room("student-sid", self.student)
        await self.manager.disconnect("student-sid")
        with self.factory() as db:
            result = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == "owner-student"))
            self.assertEqual(result.trang_thai, TrangThaiBaiLam.MAT_KET_NOI)
            self.assertEqual(result.diem_so, 0.0)

    async def test_disconnect_during_authorization_cannot_leave_ghost_membership(self):
        await self.connect()
        original = socket_service.authorize_socket_join
        started, release = threading.Event(), threading.Event()
        def delayed(payload, token):
            started.set()
            release.wait(3)
            return original(payload, token)
        with patch.object(socket_service, "authorize_socket_join", side_effect=delayed):
            task = asyncio.create_task(self.manager.join_exam_room("student-sid", self.student))
            self.assertTrue(await asyncio.to_thread(started.wait, 2))
            await self.manager.disconnect("student-sid")
            release.set()
            ack = await task
        self.assertEqual(ack["statusCode"], 409)
        self.server.enter_room.assert_not_awaited()
        self.assertEqual(self.manager.memberships, {})

    async def test_snake_case_packet_is_supported_and_ack_uses_camel_case(self):
        await self.connect()
        ack = await self.manager.join_exam_room("student-sid", {"ma_code_phong_thi": "AB12CD", "user_type": "student", "id_hs": "owner-student"})
        self.assertEqual(ack["maCodePhongThi"], "AB12CD")
        self.assertNotIn("ma_code_phong_thi", ack)

    async def test_shutdown_releases_in_memory_memberships(self):
        await self.connect()
        await self.manager.join_exam_room("student-sid", self.student)
        await self.manager.shutdown()
        self.server.shutdown.assert_awaited_once()
        self.assertFalse(self.manager.connected or self.manager.memberships or self.manager.students)

    async def test_wrapped_asgi_app_still_serves_rest_and_openapi(self):
        with TestClient(app) as client:
            self.assertEqual(client.get("/api/health").json(), {"status": "ok", "app": "Quiz AI & Exam System"})
            self.assertEqual(client.get("/openapi.json").status_code, 200)


if __name__ == "__main__":
    unittest.main()
