"""Small read/close contracts needed by Task 5.2 UI; fixed models stay unchanged."""

import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient
from sqlalchemy import event, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.core.database import get_db
from app.core.security import create_access_token
from app.models.models import CauHoi, KetQuaThi, PhongThi, TrangThaiBaiLam, TrangThaiPhongThi
from app.schemas.submission import AnswerItem, ExamSubmissionSchema
from app.services import exam_flow_service, grading_service, room_lifecycle_service
from main import fastapi_app as app
from socket_fixtures import SocketFixtures


class LeaderboardSupportTests(SocketFixtures, unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        super().setUp()
        with self.factory() as db:
            db.add(CauHoi(id_cau_hoi='q-1', id_de_thi='owner-exam', noi_dung='Water?', dap_an_a='H2O', dap_an_b='CO2', dap_an_c='O2', dap_an_d='NaCl', dap_an_dung='A'))
            db.commit()
        def override_db():
            with self.factory() as db: yield db
        app.dependency_overrides[get_db] = override_db
        self.client = TestClient(app)
        self.client.__enter__()
        self.lookup = {'idHs': 'owner-student', 'idPhongThi': 'owner-room'}
        self.owner_token = create_access_token({'sub': 'owner'})

    def tearDown(self):
        self.client.__exit__(None, None, None)
        app.dependency_overrides.clear()
        super().tearDown()

    def grade(self):
        exam_flow_service.start_exam('AB12CD', self.owner_token)
        with self.factory() as db:
            return grading_service.grade_submission(db, ExamSubmissionSchema(
                id_hs='owner-student', id_phong_thi='owner-room',
                danh_sach_cau_tra_loi=[AnswerItem(id_cau_hoi='q-1', dap_an_chon='A')],
            ))

    async def owner_join(self):
        await self.connect('teacher-sid', 'owner')
        await self.manager.join_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD', 'userType': 'teacher'})

    async def test_result_lookup_returns_own_committed_counts_even_after_room_closes(self):
        self.grade()
        await self.owner_join()
        await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD'})
        response = self.client.get('/api/submissions/result', params=self.lookup)
        self.assertEqual(response.status_code, 200, response.text)
        self.assertEqual(response.json(), {'idHs': 'owner-student', 'diemSo': 10.0, 'soCauDung': 1, 'tongSoCau': 1, 'trangThai': 'HOAN_THANH'})
        self.assertNotIn('dapAnDung', response.text)

    async def test_lookup_rejects_ungraded_unknown_and_cross_room_identity(self):
        self.assertEqual(self.client.get('/api/submissions/result', params=self.lookup).status_code, 409)
        for lookup in [{**self.lookup, 'idHs': 'missing'}, {**self.lookup, 'idPhongThi': 'other-room'}]:
            self.assertEqual(self.client.get('/api/submissions/result', params=lookup).status_code, 404)

    async def test_invalid_lookup_and_close_payloads_are_blocked_before_sql(self):
        queries = []
        def capture(*args): queries.append(args[2])
        event.listen(self.engine, 'before_cursor_execute', capture)
        try:
            self.assertEqual(self.client.get('/api/submissions/result').status_code, 422)
            self.assertEqual(self.client.get('/api/submissions/result', params={**self.lookup, 'extra': 'no'}).status_code, 422)
            ack = await self.manager.close_exam_room('unknown', {'maCodePhongThi': 'abc'})
            self.assertEqual(ack['statusCode'], 422)
        finally:
            event.remove(self.engine, 'before_cursor_execute', capture)
        self.assertEqual(queries, [])

    async def test_room_snapshot_includes_actual_scores_only_for_completed_work_and_is_owner_only(self):
        headers = {'Authorization': 'Bearer ' + self.owner_token}
        response = self.client.get('/api/rooms/owner-room', headers=headers)
        self.assertEqual(response.json()['ketQuaList'][0]['diemSo'], None)
        self.grade()
        response = self.client.get('/api/rooms/owner-room', headers=headers)
        self.assertEqual(response.json()['ketQuaList'][0]['diemSo'], 10.0)
        self.assertEqual(response.json()['ketQuaList'][0]['trangThai'], 'HOAN_THANH')
        wrong = {'Authorization': 'Bearer ' + create_access_token({'sub': 'other'})}
        self.assertEqual(self.client.get('/api/rooms/owner-room', headers=wrong).status_code, 404)

    async def test_close_commits_da_dong_before_room_broadcast_and_retry_is_idempotent(self):
        await self.owner_join()
        observed = []
        async def observe(name, packet, **kwargs):
            if name == 'room_closed':
                with self.factory() as db:
                    observed.append(db.get(PhongThi, 'owner-room').trang_thai_phong)
                self.assertEqual(kwargs, {'room': 'AB12CD'})
        self.server.emit.side_effect = observe
        ack = await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD'})
        self.assertEqual(ack['trangThaiPhong'], 'DA_DONG')
        self.assertEqual(observed, [TrangThaiPhongThi.DA_DONG])
        again = await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD'})
        self.assertEqual(again, ack)
        with self.factory() as db:
            self.assertEqual(db.get(PhongThi, 'other-room').trang_thai_phong, TrangThaiPhongThi.DANG_CHO)

    async def test_student_and_foreign_teacher_cannot_close_room(self):
        await self.connect('student')
        await self.manager.join_exam_room('student', self.student)
        self.assertEqual((await self.manager.close_exam_room('student', {'maCodePhongThi': 'AB12CD'}))['statusCode'], 403)
        await self.owner_join()
        self.assertEqual((await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'QR34ST'}))['statusCode'], 403)
        self.sessions['teacher-sid']['token'] = create_access_token({'sub': 'other'})
        self.assertEqual((await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD'}))['statusCode'], 403)

    async def test_failed_close_commit_rolls_back_without_closed_broadcast(self):
        await self.owner_join()
        self.server.emit.reset_mock()
        with patch.object(Session, 'commit', side_effect=SQLAlchemyError('private failure')):
            ack = await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD'})
        self.assertEqual(ack['statusCode'], 503)
        self.server.emit.assert_not_awaited()
        with self.factory() as db:
            self.assertEqual(db.get(PhongThi, 'owner-room').trang_thai_phong, TrangThaiPhongThi.DANG_CHO)

    async def test_close_does_not_change_completed_grades_or_grade_unsubmitted_students(self):
        self.grade()
        await self.owner_join()
        await self.manager.close_exam_room('teacher-sid', {'maCodePhongThi': 'AB12CD'})
        with self.factory() as db:
            row = db.scalar(select(KetQuaThi).where(KetQuaThi.id_hs == 'owner-student'))
            self.assertEqual((row.trang_thai, row.diem_so), (TrangThaiBaiLam.HOAN_THANH, 10.0))
        response = self.client.post('/api/rooms/join', json={'hoVaTen': 'New student', 'maCodePhongThi': 'AB12CD'})
        self.assertEqual(response.status_code, 409)
