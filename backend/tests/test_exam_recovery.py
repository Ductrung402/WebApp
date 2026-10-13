"""ERR_02 commits, room isolation, deadlines, multi-tab and reconnect races."""

import asyncio
import threading
import unittest
from unittest.mock import patch

from sqlalchemy import event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.models.models import CauHoi, HocSinh, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.services import exam_flow_service, exam_recovery_service
from socket_fixtures import SocketFixtures


class RecoveryTests(SocketFixtures, unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        super().setUp()
        with self.factory() as db:
            db.add(CauHoi(id_cau_hoi="q-1", id_de_thi="owner-exam", noi_dung="Water?", dap_an_a="H2O", dap_an_b="CO2", dap_an_c="O2", dap_an_d="NaCl", dap_an_dung="A"))
            db.commit()
        self.packet = {"maCodePhongThi": "AB12CD", "idHs": "owner-student"}

    async def join_student(self, sid="student-sid"):
        await self.connect(sid)
        self.assertEqual((await self.manager.join_exam_room(sid, self.student))["status"], "ok")

    async def start(self):
        await self.connect("teacher-sid", "owner")
        await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        ack = await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(ack["status"], "ok")
        return ack["exam"]

    def state(self, student_id="owner-student"):
        with self.factory() as db:
            row = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == student_id))
            return row.trang_thai, row.diem_so

    async def recover(self, sid="new-sid"):
        await self.join_student(sid)
        return await self.manager.reconnect_exam(sid, self.packet)

    async def test_disconnect_commits_before_private_teacher_notification_and_freezes_time(self):
        run = await self.start()
        await self.join_student()
        observed = []
        async def observe(name, packet, **kwargs):
            if name == "student_disconnected":
                observed.append((packet, kwargs, self.state()))
        self.server.emit.side_effect = observe
        with patch.object(exam_flow_service, "now_ms", return_value=run["startsAt"] + 15000):
            await self.manager.disconnect("student-sid")
        packet, kwargs, state = observed[0]
        self.assertEqual(state, (TrangThaiBaiLam.MAT_KET_NOI, 0.0))
        self.assertEqual(kwargs, {"room": "teachers:AB12CD"})
        self.assertEqual(packet["idHs"], "owner-student")
        self.assertEqual(packet["trangThai"], "MAT_KET_NOI")
        self.assertEqual(packet["frozenRemainingSeconds"], 1785)
        self.assertEqual(packet["disconnectedAt"], run["startsAt"] + 15000)
        self.assertEqual(self.state("other-student")[0], TrangThaiBaiLam.DANG_THI)

    async def test_multiple_tabs_only_last_socket_marks_disconnected_and_duplicate_callback_is_safe(self):
        await self.join_student("one")
        await self.join_student("two")
        with patch.object(exam_recovery_service, "mark_disconnected", wraps=exam_recovery_service.mark_disconnected) as mark:
            await self.manager.disconnect("one")
            self.assertEqual(self.state()[0], TrangThaiBaiLam.DANG_THI)
            mark.assert_not_called()
            await self.manager.disconnect("two")
            self.assertEqual(self.state()[0], TrangThaiBaiLam.MAT_KET_NOI)
            await self.manager.disconnect("two")
            self.assertEqual(mark.call_count, 1)

    async def test_reconnect_restores_state_and_public_questions_without_extending_deadline(self):
        run = await self.start()
        await self.join_student()
        with patch.object(exam_flow_service, "now_ms", return_value=run["startsAt"] + 10000):
            await self.manager.disconnect("student-sid")
        with patch.object(exam_flow_service, "now_ms", return_value=run["startsAt"] + 25000):
            ack = await self.recover()
        self.assertEqual(self.state()[0], TrangThaiBaiLam.DANG_THI)
        self.assertEqual(ack["remainingSeconds"], 1775)
        self.assertFalse(ack["examExpired"])
        self.assertEqual(ack["exam"]["endsAt"], run["endsAt"])
        self.assertEqual(ack["studentState"]["stateVersion"], 2)
        self.assertEqual(ack["studentState"]["frozenRemainingSeconds"], 1790)
        self.assertNotIn("dapAnDung", str(ack))
        self.assertNotIn("dap_an_dung", str(ack))
        with self.factory() as db:
            self.assertEqual(len(db.scalars(select(HocSinh)).all()), 2)
            self.assertEqual(len(db.scalars(select(KetQuaThi)).all()), 2)

    async def test_waiting_room_can_recover_without_exam_or_deadline(self):
        await self.join_student()
        await self.manager.disconnect("student-sid")
        ack = await self.recover()
        self.assertIsNone(ack["exam"])
        self.assertIsNone(ack["remainingSeconds"])
        self.assertEqual(ack["studentState"]["trangThai"], "DANG_THI")

    async def test_expired_exam_does_not_restore_work_but_returns_zero_and_safe_exam(self):
        run = await self.start()
        await self.join_student()
        await self.manager.disconnect("student-sid")
        with patch.object(exam_flow_service, "now_ms", return_value=run["endsAt"]):
            ack = await self.recover()
        self.assertEqual(self.state()[0], TrangThaiBaiLam.MAT_KET_NOI)
        self.assertEqual(ack["remainingSeconds"], 0)
        self.assertTrue(ack["examExpired"])
        self.assertEqual(ack["exam"]["endsAt"], run["endsAt"])

    async def test_completed_result_is_never_marked_or_resurrected(self):
        await self.start()
        await self.join_student()
        with self.factory() as db:
            result = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == "owner-student"))
            result.trang_thai = TrangThaiBaiLam.HOAN_THANH
            result.diem_so = 8.5
            db.commit()
        self.server.emit.reset_mock()
        await self.manager.disconnect("student-sid")
        self.assertFalse(any(call.args[0] == "student_disconnected" for call in self.server.emit.call_args_list))
        ack = await self.recover()
        self.assertEqual(ack["studentState"]["trangThai"], "HOAN_THANH")
        self.assertEqual(self.state(), (TrangThaiBaiLam.HOAN_THANH, 8.5))

    async def test_invalid_or_mismatched_identity_never_calls_recovery_service(self):
        await self.join_student()
        with patch.object(exam_recovery_service, "reconnect_exam", side_effect=AssertionError("Unexpected SQL")) as recover:
            for payload in [{}, {**self.packet, "idHs": 123}, {**self.packet, "maCodePhongThi": "bad"}, {**self.packet, "extra": True}]:
                self.assertEqual((await self.manager.reconnect_exam("student-sid", payload))["statusCode"], 422)
            self.assertEqual((await self.manager.reconnect_exam("student-sid", {**self.packet, "idHs": "other-student"}))["statusCode"], 403)
            self.assertEqual((await self.manager.reconnect_exam("student-sid", {**self.packet, "maCodePhongThi": "QR34ST"}))["statusCode"], 403)
            recover.assert_not_called()

    async def test_teacher_and_unjoined_socket_cannot_reconnect_student_work(self):
        await self.start()
        self.assertEqual((await self.manager.reconnect_exam("teacher-sid", self.packet))["statusCode"], 403)
        await self.connect("guest")
        self.assertEqual((await self.manager.reconnect_exam("guest", self.packet))["statusCode"], 403)

    async def test_closed_room_cannot_restore_a_previously_joined_student(self):
        await self.join_student()
        with self.factory() as db:
            db.get(PhongThi, "owner-room").trang_thai_phong = TrangThaiPhongThi.DA_DONG
            db.commit()
        self.assertEqual((await self.manager.reconnect_exam("student-sid", self.packet))["statusCode"], 409)

    async def test_failed_disconnect_commit_rolls_back_and_reports_error_without_false_state_event(self):
        await self.join_student()
        self.server.emit.reset_mock()
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("private details")):
            await self.manager.disconnect("student-sid")
        self.assertEqual(self.state()[0], TrangThaiBaiLam.DANG_THI)
        names = [call.args[0] for call in self.server.emit.call_args_list]
        self.assertNotIn("student_disconnected", names)
        self.assertIn("student_connection_error", names)
        self.assertNotIn("private details", str(self.server.emit.call_args_list))
        await self.connect("teacher-sid", "owner")
        await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        snapshots = [call.args[1] for call in self.server.emit.call_args_list if call.args[0] == "student_states_updated"]
        self.assertEqual(snapshots[-1]["hocSinhList"][0]["trangThai"], "DANG_THI")

    async def test_failed_recovery_commit_keeps_disconnected_and_never_broadcasts_restored_state(self):
        await self.join_student()
        await self.manager.disconnect("student-sid")
        await self.join_student("new-sid")
        self.server.emit.reset_mock()
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("Commit failed")):
            ack = await self.manager.reconnect_exam("new-sid", self.packet)
        self.assertEqual(ack["statusCode"], 503)
        self.assertEqual(self.state()[0], TrangThaiBaiLam.MAT_KET_NOI)
        self.server.emit.assert_not_awaited()

    async def test_reconnect_waits_for_inflight_disconnect_then_restores_the_final_state(self):
        await self.start()
        await self.join_student()
        original = exam_recovery_service.mark_disconnected
        started, release = threading.Event(), threading.Event()
        def delayed(member, disconnected_at):
            started.set()
            release.wait(3)
            return original(member, disconnected_at)
        with patch.object(exam_recovery_service, "mark_disconnected", side_effect=delayed):
            disconnect = asyncio.create_task(self.manager.disconnect("student-sid"))
            self.assertTrue(await asyncio.to_thread(started.wait, 2))
            recovery = asyncio.create_task(self.recover())
            await asyncio.sleep(0.02)
            release.set()
            await disconnect
            ack = await recovery
        self.assertEqual(ack["studentState"]["trangThai"], "DANG_THI")
        self.assertEqual(self.state()[0], TrangThaiBaiLam.DANG_THI)

    async def test_teacher_snapshot_after_reload_contains_offline_students_and_sql_is_scoped_off_loop(self):
        await self.join_student()
        await self.manager.disconnect("student-sid")
        queries = []
        main_thread = threading.get_ident()
        def capture(connection, cursor, statement, parameters, context, executemany):
            if statement.startswith("SELECT"):
                queries.append((statement, threading.get_ident()))
        event.listen(self.engine, "before_cursor_execute", capture)
        try:
            await self.connect("teacher-sid", "owner")
            await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        finally:
            event.remove(self.engine, "before_cursor_execute", capture)
        packet = next(call.args[1] for call in self.server.emit.call_args_list if call.args[0] == "student_states_updated")
        self.assertEqual([student["idHs"] for student in packet["hocSinhList"]], ["owner-student"])
        self.assertEqual(packet["hocSinhList"][0]["trangThai"], "MAT_KET_NOI")
        self.assertTrue(all("WHERE" in sql and thread != main_thread for sql, thread in queries))

    async def test_teacher_disconnect_does_not_update_student_state(self):
        await self.start()
        with patch.object(exam_recovery_service, "mark_disconnected") as mark:
            await self.manager.disconnect("teacher-sid")
            mark.assert_not_called()
        self.assertEqual(self.state()[0], TrangThaiBaiLam.DANG_THI)

    async def test_missing_deadline_still_marks_disconnect_but_cannot_restore_an_active_exam(self):
        with self.factory() as db:
            db.get(PhongThi, "owner-room").trang_thai_phong = TrangThaiPhongThi.DANG_THI
            db.commit()
        await self.join_student()
        await self.manager.disconnect("student-sid")
        self.assertEqual(self.state()[0], TrangThaiBaiLam.MAT_KET_NOI)
        ack = await self.recover()
        self.assertEqual(ack["statusCode"], 503)
        self.assertEqual(self.state()[0], TrangThaiBaiLam.MAT_KET_NOI)

    async def test_teacher_snapshot_waits_for_disconnect_commit_instead_of_reading_prepared_state(self):
        await self.start()
        await self.join_student()
        original = exam_recovery_service.mark_disconnected
        started, release = threading.Event(), threading.Event()
        def delayed(member, disconnected_at):
            started.set()
            release.wait(3)
            return original(member, disconnected_at)
        with patch.object(exam_recovery_service, "mark_disconnected", side_effect=delayed), patch.object(exam_recovery_service, "read_student_states", wraps=exam_recovery_service.read_student_states) as read:
            disconnect = asyncio.create_task(self.manager.disconnect("student-sid"))
            self.assertTrue(await asyncio.to_thread(started.wait, 2))
            snapshot = asyncio.create_task(self.manager.sync_exam("teacher-sid", {"maCodePhongThi": "AB12CD"}))
            await asyncio.sleep(0.02)
            read.assert_not_called()
            release.set()
            await disconnect
            self.assertEqual((await snapshot)["status"], "ok")
        states = [call.args[1] for call in self.server.emit.call_args_list if call.args[0] == "student_states_updated"]
        self.assertEqual(states[-1]["hocSinhList"][0]["trangThai"], "MAT_KET_NOI")
