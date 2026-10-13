"""REST grading, immutable answers, atomic score/state updates and notifications."""

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient
from sqlalchemy import event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import create_access_token
from app.models.models import CauHoi, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.schemas.exam_flow import StoredSubmissionSchema
from app.schemas.submission import AnswerItem, ExamSubmissionSchema, SubmissionResultResponseSchema
from app.services import exam_flow_service, grading_service
from main import fastapi_app as app
from socket_fixtures import SocketFixtures


class SubmissionTests(SocketFixtures, unittest.TestCase):
    def setUp(self):
        super().setUp()
        with self.factory() as db:
            for index, correct in enumerate(['A', 'B', 'C'], 1):
                db.add(CauHoi(id_cau_hoi=f'q-{index}', id_de_thi='owner-exam', noi_dung=f'Question {index}', dap_an_a='A option', dap_an_b='B option', dap_an_c='C option', dap_an_d='D option', dap_an_dung=correct))
            db.commit()
        self.run = exam_flow_service.start_exam('AB12CD', create_access_token({'sub': 'owner'}))
        def override_db():
            with self.factory() as db:
                yield db
        app.dependency_overrides[get_db] = override_db
        self.notifications = []
        async def observe(outcome, manager):
            self.notifications.append((outcome.event.model_dump(mode='json', by_alias=True), self.state()))
        self.notify = AsyncMock(side_effect=observe)
        self.notify_patch = patch.object(grading_service, 'emit_student_submitted', self.notify)
        self.notify_patch.start()
        self.client = TestClient(app)
        self.client.__enter__()
        self.payload = {'idHs': 'owner-student', 'idPhongThi': 'owner-room', 'danhSachCauTraLoi': [
            {'idCauHoi': 'q-1', 'dapAnChon': 'A'}, {'idCauHoi': 'q-2', 'dapAnChon': 'B'}, {'idCauHoi': 'q-3', 'dapAnChon': None},
        ]}

    def tearDown(self):
        self.client.__exit__(None, None, None)
        app.dependency_overrides.clear()
        self.notify_patch.stop()
        super().tearDown()

    def state(self):
        with self.factory() as db:
            row = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == 'owner-student'))
            return row.trang_thai, row.diem_so

    def submit(self, payload=None):
        return self.client.post('/api/submissions/submit', json=self.payload if payload is None else payload)

    def test_grade_uses_actual_question_count_rounds_and_notifies_after_commit(self):
        response = self.submit()
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), {'idHs': 'owner-student', 'diemSo': 6.67, 'soCauDung': 2, 'tongSoCau': 3, 'trangThai': 'HOAN_THANH'})
        self.assertEqual(self.state(), (TrangThaiBaiLam.HOAN_THANH, 6.67))
        self.assertEqual(self.notifications[0][1], self.state())
        self.assertNotIn('dapAnDung', response.text)
        self.assertNotIn('dap_an_dung', str(self.notifications))

    def test_empty_or_null_answers_score_zero(self):
        response = self.submit({**self.payload, 'danhSachCauTraLoi': []})
        self.assertEqual(response.json()['diemSo'], 0.0)
        self.assertEqual(response.json()['soCauDung'], 0)

    def test_all_correct_scores_ten(self):
        payload = {**self.payload, 'danhSachCauTraLoi': [{'idCauHoi': f'q-{index}', 'dapAnChon': correct} for index, correct in enumerate(['A', 'B', 'C'], 1)]}
        self.assertEqual(self.submit(payload).json()['diemSo'], 10.0)

    def test_duplicate_submission_returns_original_counts_and_score_for_different_answers(self):
        original = self.submit().json()
        changed = {**self.payload, 'danhSachCauTraLoi': [{'idCauHoi': 'q-3', 'dapAnChon': 'C'}]}
        self.assertEqual(self.submit(changed).json(), original)
        self.assertEqual(self.state(), (TrangThaiBaiLam.HOAN_THANH, 6.67))

    def test_legacy_receipt_wins_over_new_rest_answers(self):
        exam_flow_service.runtime_store.receive_submission(StoredSubmissionSchema(
            submission_id='legacy-receipt', run_id=self.run.run_id, id_hs='owner-student',
            received_at=self.run.starts_at, received_late=False, reason='manual', answers={'q-3': 'C'},
        ))
        response = self.submit()
        self.assertEqual(response.json()['diemSo'], 3.33)
        self.assertEqual(response.json()['soCauDung'], 1)

    def test_malformed_payloads_and_duplicate_ids_are_rejected_before_any_sql(self):
        queries = []
        def capture(*args): queries.append(args[2])
        event.listen(self.engine, 'before_cursor_execute', capture)
        try:
            for payload in [
                {}, {**self.payload, 'idHs': 12}, {**self.payload, 'idPhongThi': ''}, {**self.payload, 'extra': True},
                {**self.payload, 'danhSachCauTraLoi': [{'idCauHoi': 'q-1'}]},
                {**self.payload, 'danhSachCauTraLoi': [{'idCauHoi': 'q-1', 'dapAnChon': 'E'}]},
                {**self.payload, 'danhSachCauTraLoi': [{'idCauHoi': 'q-1', 'dapAnChon': 'a'}]},
                {**self.payload, 'danhSachCauTraLoi': [self.payload['danhSachCauTraLoi'][0]] * 2},
            ]:
                self.assertEqual(self.submit(payload).status_code, 422)
        finally:
            event.remove(self.engine, 'before_cursor_execute', capture)
        self.assertEqual(queries, [])
        self.notify.assert_not_awaited()

    def test_unknown_student_room_and_cross_room_identity_are_rejected(self):
        for payload in [{**self.payload, 'idHs': 'missing'}, {**self.payload, 'idPhongThi': 'missing'}, {**self.payload, 'idHs': 'other-student'}]:
            self.assertEqual(self.submit(payload).status_code, 404)
        self.assertEqual(self.state(), (TrangThaiBaiLam.DANG_THI, 0.0))

    def test_foreign_question_is_rejected_without_saving_a_receipt_or_grade(self):
        response = self.submit({**self.payload, 'danhSachCauTraLoi': [{'idCauHoi': 'foreign-question', 'dapAnChon': 'A'}]})
        self.assertEqual(response.status_code, 422)
        self.assertIsNone(exam_flow_service.runtime_store.read_submission(self.run.run_id, 'owner-student'))
        self.assertIsNone(exam_flow_service.runtime_store.read_grade('owner-room', 'owner-student'))

    def test_waiting_and_closed_rooms_do_not_accept_new_work(self):
        for state in [TrangThaiPhongThi.DANG_CHO, TrangThaiPhongThi.DA_DONG]:
            with self.factory() as db:
                db.get(PhongThi, 'owner-room').trang_thai_phong = state
                db.commit()
            self.assertEqual(self.submit().status_code, 409)
        self.notify.assert_not_awaited()

    def test_disconnected_result_and_late_submission_can_be_finalized(self):
        with self.factory() as db:
            db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == 'owner-student')).trang_thai = TrangThaiBaiLam.MAT_KET_NOI
            db.commit()
        with patch.object(exam_flow_service, 'now_ms', return_value=self.run.ends_at + 1000):
            response = self.submit()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.state(), (TrangThaiBaiLam.HOAN_THANH, 6.67))
        self.assertTrue(exam_flow_service.runtime_store.read_submission(self.run.run_id, 'owner-student').received_late)

    def test_commit_failure_rolls_back_both_fields_hides_receipt_and_retry_uses_frozen_answers(self):
        with patch.object(Session, 'commit', side_effect=SQLAlchemyError('private details')):
            response = self.submit()
        self.assertEqual(response.status_code, 503)
        self.assertNotIn('private details', response.text)
        self.assertEqual(self.state(), (TrangThaiBaiLam.DANG_THI, 0.0))
        self.notify.assert_not_awaited()
        with self.factory() as db:
            member = grading_service.socket_service.authorize_socket_join(self._student_schema(), None)
        self.assertIsNone(exam_flow_service.sync_exam(member, None).submission)
        changed = {**self.payload, 'danhSachCauTraLoi': []}
        self.assertEqual(self.submit(changed).json()['diemSo'], 6.67)

    def _student_schema(self):
        from app.schemas.socket import SocketJoinSchema
        return SocketJoinSchema.model_validate(self.student)

    def test_runtime_write_failure_keeps_score_and_state_unchanged(self):
        with patch.object(exam_flow_service.runtime_store, 'write_grade', side_effect=OSError('disk failure')):
            response = self.submit()
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.state(), (TrangThaiBaiLam.DANG_THI, 0.0))
        self.notify.assert_not_awaited()

    def test_failure_after_flush_rolls_back_real_update(self):
        original_flush = Session.flush
        def fail_after_flush(db, *args, **kwargs):
            original_flush(db, *args, **kwargs)
            raise RuntimeError('unexpected')
        with patch.object(Session, 'flush', fail_after_flush):
            with self.assertRaises(RuntimeError): self.submit()
        self.assertEqual(self.state(), (TrangThaiBaiLam.DANG_THI, 0.0))
        self.notify.assert_not_awaited()

    def test_invalid_or_empty_answer_keys_cannot_be_graded(self):
        with self.factory() as db:
            db.get(CauHoi, 'q-1').dap_an_dung = 'X'
            db.commit()
        self.assertEqual(self.submit().status_code, 503)
        with self.factory() as db:
            for row in db.scalars(select(CauHoi).where(CauHoi.id_de_thi == 'owner-exam')): db.delete(row)
            db.commit()
        self.assertEqual(self.submit().status_code, 409)
        self.notify.assert_not_awaited()

    def test_missing_completed_metadata_does_not_recalculate_a_saved_score(self):
        self.submit()
        exam_flow_service.runtime_store._grade_path('owner-room', 'owner-student').unlink()
        self.assertEqual(self.submit({**self.payload, 'danhSachCauTraLoi': []}).status_code, 503)
        self.assertEqual(self.state(), (TrangThaiBaiLam.HOAN_THANH, 6.67))

    def test_snake_case_payload_and_openapi_match_the_public_contract(self):
        payload = ExamSubmissionSchema.model_validate(self.payload).model_dump()
        response = self.submit(payload)
        self.assertEqual(response.status_code, 200)
        schema = self.client.get('/openapi.json').json()['components']['schemas']['SubmissionResultResponseSchema']
        self.assertEqual(set(schema['properties']), {'idHs', 'diemSo', 'soCauDung', 'tongSoCau', 'trangThai'})
        self.assertEqual(SubmissionResultResponseSchema.model_validate_json(response.text).tong_so_cau, 3)

    def test_transport_failure_does_not_undo_committed_grade_and_retry_republishes(self):
        # Exercise the real post-commit notification wrapper rather than our observer.
        from app.core.socket_manager import socket_manager
        self.notify_patch.stop()
        try:
            with patch.object(socket_manager.server, 'emit', new=AsyncMock(side_effect=RuntimeError('transport failed'))):
                self.assertEqual(self.submit().status_code, 200)
            self.assertEqual(self.state(), (TrangThaiBaiLam.HOAN_THANH, 6.67))
            with patch.object(socket_manager.server, 'emit', new=AsyncMock()) as emit:
                self.assertEqual(self.submit().json()['diemSo'], 6.67)
                self.assertEqual(emit.call_args.args[0], 'student_submitted')
                self.assertEqual(emit.call_args.kwargs, {'room': 'teachers:AB12CD'})
        finally:
            self.notify_patch.start()
