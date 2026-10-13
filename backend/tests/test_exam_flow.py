"""Real-time transition, public-question security, deadlines and receipt-only writes."""

import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

from sqlalchemy import event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.exam_runtime import ExamRuntimeStore
from app.models.models import CauHoi, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.services import exam_flow_service
from socket_fixtures import SocketFixtures


class ExamFlowTests(SocketFixtures, unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        super().setUp()
        self.directory = tempfile.TemporaryDirectory(prefix="exam-flow-test-")
        self.store = ExamRuntimeStore(self.directory.name)
        self.runtime_patch = patch.object(exam_flow_service, "runtime_store", self.store)
        self.runtime_patch.start()
        with self.factory() as db:
            db.add(CauHoi(id_cau_hoi="q-1", id_de_thi="owner-exam", noi_dung="Water formula?", dap_an_a="H2O", dap_an_b="CO2", dap_an_c="O2", dap_an_d="NaCl", dap_an_dung="A"))
            db.commit()

    def tearDown(self):
        self.runtime_patch.stop()
        self.directory.cleanup()
        super().tearDown()

    async def start(self):
        await self.connect("teacher-sid", "owner")
        await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        response = await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(response["status"], "ok", response)
        return response["exam"]

    async def admitted_student(self):
        await self.connect("student-sid")
        await self.manager.join_exam_room("student-sid", self.student)

    async def test_start_commits_state_before_broadcast_and_excludes_answer_keys(self):
        observations = []
        async def observe(event_name, packet, **kwargs):
            if event_name == "exam_started":
                with self.factory() as db:
                    observations.append(db.get(PhongThi, "owner-room").trang_thai_phong)
                self.assertEqual(kwargs["room"], "AB12CD")
        self.server.emit.side_effect = observe
        run = await self.start()
        self.assertEqual(observations, [TrangThaiPhongThi.DANG_THI])
        self.assertEqual(run["trangThaiPhong"], "DANG_THI")
        self.assertEqual(run["endsAt"] - run["startsAt"], 30 * 60000)
        self.assertEqual(set(run["cauHoiList"][0]), {"idCauHoi", "noiDung", "dapAnA", "dapAnB", "dapAnC", "dapAnD"})
        self.assertNotIn("dapAnDung", json.dumps(run))
        self.assertNotIn("dap_an_dung", json.dumps(run))

    async def test_start_question_query_has_where_and_never_selects_correct_answer(self):
        queries = []
        main_thread = threading.get_ident()
        def capture(connection, cursor, statement, parameters, context, executemany):
            if "FROM cau_hoi" in statement:
                queries.append((statement, threading.get_ident()))
        event.listen(self.engine, "before_cursor_execute", capture)
        try:
            await self.start()
        finally:
            event.remove(self.engine, "before_cursor_execute", capture)
        self.assertTrue(queries)
        self.assertTrue(all("WHERE" in query and "dap_an_dung" not in query for query, thread in queries))
        self.assertTrue(all(thread != main_thread for query, thread in queries))

    async def test_student_and_unjoined_teacher_cannot_start(self):
        await self.admitted_student()
        response = await self.manager.start_exam("student-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(response["statusCode"], 403)
        await self.connect("teacher-sid", "owner")
        response = await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(response["statusCode"], 403)

    async def test_invalid_start_and_submission_are_rejected_before_sql(self):
        with patch.object(exam_flow_service, "start_exam", side_effect=AssertionError("Invalid start queried SQL")), patch.object(exam_flow_service, "submit_exam", side_effect=AssertionError("Invalid submit queried SQL")):
            response = await self.manager.start_exam("unknown", {"maCodePhongThi": "abc123"})
            self.assertEqual(response["statusCode"], 422)
            response = await self.manager.submit_exam("unknown", {"maCodePhongThi": "AB12CD", "runId": "run", "answers": {"q-1": "E"}, "reason": "manual"})
            self.assertEqual(response["statusCode"], 422)

    async def test_duplicate_start_preserves_run_and_deadline(self):
        run = await self.start()
        again = await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(again["exam"]["runId"], run["runId"])
        self.assertEqual(again["exam"]["startsAt"], run["startsAt"])
        self.assertEqual(again["exam"]["endsAt"], run["endsAt"])

    async def test_sync_restores_deadline_after_runtime_store_recreation(self):
        run = await self.start()
        await self.admitted_student()
        with patch.object(exam_flow_service, "runtime_store", ExamRuntimeStore(self.directory.name)):
            response = await self.manager.sync_exam("student-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(response["exam"]["runId"], run["runId"])
        self.assertEqual(response["exam"]["endsAt"], run["endsAt"])

    async def test_commit_failure_rolls_back_state_and_never_broadcasts_prepared_run(self):
        await self.connect("teacher-sid", "owner")
        await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        self.server.emit.reset_mock()
        with patch.object(Session, "commit", side_effect=SQLAlchemyError("Commit failure")):
            response = await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(response["statusCode"], 503)
        self.server.emit.assert_not_awaited()
        with self.factory() as db:
            self.assertEqual(db.get(PhongThi, "owner-room").trang_thai_phong, TrangThaiPhongThi.DANG_CHO)
        sync = await self.manager.sync_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertIsNone(sync["exam"])

    async def test_runtime_write_failure_does_not_start_room(self):
        await self.connect("teacher-sid", "owner")
        await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        with patch.object(self.store, "write_run", side_effect=OSError("Disk failure")):
            response = await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(response["statusCode"], 503)
        with self.factory() as db:
            self.assertEqual(db.get(PhongThi, "owner-room").trang_thai_phong, TrangThaiPhongThi.DANG_CHO)

    async def test_empty_exam_cannot_start(self):
        await self.connect("other-sid", "other")
        await self.manager.join_exam_room("other-sid", {"maCodePhongThi": "QR34ST", "userType": "teacher"})
        response = await self.manager.start_exam("other-sid", {"maCodePhongThi": "QR34ST"})
        self.assertEqual(response["statusCode"], 409)

    async def test_submit_grades_first_snapshot_once_and_never_overwrites_result(self):
        run = await self.start()
        await self.admitted_student()
        payload = {"maCodePhongThi": "AB12CD", "runId": run["runId"], "answers": {"q-1": "B"}, "reason": "manual"}
        response = await self.manager.submit_exam("student-sid", payload)
        self.assertEqual(response["status"], "ok")
        again = await self.manager.submit_exam("student-sid", {**payload, "answers": {"q-1": "A"}})
        self.assertEqual(again["submission"]["submissionId"], response["submission"]["submissionId"])
        self.assertTrue(again["submission"]["duplicate"])
        self.assertEqual(self.store.read_submission(run["runId"], "owner-student").answers, {"q-1": "B"})
        with self.factory() as db:
            result = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == "owner-student"))
            self.assertEqual(result.trang_thai, TrangThaiBaiLam.HOAN_THANH)
            self.assertEqual(result.diem_so, 0.0)
        self.assertNotIn("answers", response["submission"])
        self.assertEqual(response["result"]["trangThai"], TrangThaiBaiLam.HOAN_THANH)

    async def test_timeout_submission_receipt_and_sync_recovery(self):
        run = await self.start()
        await self.admitted_student()
        with patch.object(exam_flow_service, "now_ms", return_value=run["endsAt"] + 1000):
            response = await self.manager.submit_exam("student-sid", {"maCodePhongThi": "AB12CD", "runId": run["runId"], "answers": {}, "reason": "timeout"})
        self.assertTrue(response["submission"]["receivedLate"])
        sync = await self.manager.sync_exam("student-sid", {"maCodePhongThi": "AB12CD"})
        self.assertEqual(sync["submission"]["submissionId"], response["submission"]["submissionId"])

    async def test_unknown_question_wrong_run_and_teacher_submission_are_denied(self):
        run = await self.start()
        await self.admitted_student()
        base = {"maCodePhongThi": "AB12CD", "runId": run["runId"], "answers": {"q-1": "A"}, "reason": "manual"}
        self.assertEqual((await self.manager.submit_exam("student-sid", {**base, "answers": {"foreign-question": "A"}}))["statusCode"], 422)
        self.assertEqual((await self.manager.submit_exam("student-sid", {**base, "runId": "another-run"}))["statusCode"], 409)
        self.assertEqual((await self.manager.submit_exam("teacher-sid", base))["statusCode"], 403)
        self.assertEqual(list(Path(self.directory.name).glob("submission-*")), [])

    async def test_active_room_without_runtime_does_not_restart_clock(self):
        with self.factory() as db:
            db.get(PhongThi, "owner-room").trang_thai_phong = TrangThaiPhongThi.DANG_THI
            db.commit()
        await self.connect("teacher-sid", "owner")
        await self.manager.join_exam_room("teacher-sid", {"maCodePhongThi": "AB12CD", "userType": "teacher"})
        self.assertEqual((await self.manager.start_exam("teacher-sid", {"maCodePhongThi": "AB12CD"}))["statusCode"], 503)
