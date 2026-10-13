"""Socket transport and in-memory membership. No SQL runs in the event loop."""

import asyncio
import logging

from fastapi import HTTPException
from pydantic import ValidationError
import socketio

from app.schemas.socket import (
    SocketAuthSchema, SocketErrorSchema, SocketJoinAckSchema, SocketJoinSchema,
    SocketMembershipSchema, SocketStudentsSchema,
    SocketStudentStatesSchema, StudentRecoveryErrorSchema,
)
from app.services import socket_service
from app.services import exam_flow_service
from app.services import exam_recovery_service
from app.services import grading_service
from app.services import room_lifecycle_service
from app.schemas.exam_flow import ExamRoomEventSchema, ExamSubmitSchema, ExamSyncSchema
from app.schemas.exam_recovery import ReconnectExamSchema

logger = logging.getLogger("uvicorn.error.socket")
sio = socketio.AsyncServer(async_mode="asgi", cors_allowed_origins="*")


class ExamSocketManager:
    def __init__(self, server):
        self.server = server
        self.lock = asyncio.Lock()
        self.connected = set()
        self.memberships: dict[str, SocketMembershipSchema] = {}
        # room code -> student ID -> {student DTO, set of socket IDs}
        self.students = {}
        self.flow_locks = {}
        server.on("connect", self.connect)
        server.on("disconnect", self.disconnect)
        server.on("join_exam_room", self.join_exam_room)
        server.on("start_exam", self.start_exam)
        server.on("sync_exam", self.sync_exam)
        server.on("submit_exam", self.submit_exam)
        server.on("reconnect_exam", self.reconnect_exam)
        server.on("close_exam_room", self.close_exam_room)

    @staticmethod
    def teacher_room(code):
        return f"teachers:{code}"

    @staticmethod
    def error(code, detail):
        return SocketErrorSchema(status_code=code, detail=detail).model_dump(by_alias=True)

    async def connect(self, sid, environ, auth=None):
        try:
            packet = SocketAuthSchema.model_validate({} if auth is None else auth)
        except ValidationError as exc:
            raise socketio.exceptions.ConnectionRefusedError("Invalid connection data.") from exc
        await self.server.save_session(sid, {"token": packet.token})
        async with self.lock:
            self.connected.add(sid)
        logger.info("socket_connected sid=%s", sid)

    def _release(self, sid):
        previous = self.memberships.pop(sid, None)
        if previous and previous.user_type == "student":
            room = self.students.get(previous.ma_code_phong_thi, {})
            entry = room.get(previous.id_hs)
            if entry:
                entry["sids"].discard(sid)
                if not entry["sids"]:
                    room.pop(previous.id_hs)
            if not room:
                self.students.pop(previous.ma_code_phong_thi, None)
        return previous

    async def _broadcast_students(self, code):
        roster = [entry["student"] for entry in self.students.get(code, {}).values()]
        roster.sort(key=lambda student: (student.ho_va_ten.casefold(), student.id_hs))
        packet = SocketStudentsSchema(ma_code_phong_thi=code, hoc_sinh_list=roster)
        await self.server.emit(
            "room_students_updated", packet.model_dump(by_alias=True),
            room=self.teacher_room(code),
        )

    async def join_exam_room(self, sid, data):
        try:
            payload = SocketJoinSchema.model_validate(data)  # ERR_03: before any SQL.
        except ValidationError:
            return self.error(422, "Invalid room join payload.")
        try:
            auth = await self.server.get_session(sid)
            # Each thread opens/closes its own SQLAlchemy session; only DTOs return.
            membership = await asyncio.to_thread(
                socket_service.authorize_socket_join, payload, auth.get("token"),
            )
            async with self.flow_locks.setdefault(membership.ma_code_phong_thi, asyncio.Lock()):
                states = await asyncio.to_thread(exam_recovery_service.read_student_states, membership) if membership.user_type == "teacher" else None
                return await self._join_membership(sid, membership, states)
        except HTTPException as exc:
            return self.error(exc.status_code, str(exc.detail))
        except KeyError:
            return self.error(409, "Socket is no longer connected.")
        except Exception:
            logger.error("socket_join_failed sid=%s", sid)
            return self.error(503, "Unable to join the room. Please retry.")

    async def _join_membership(self, sid, membership, states):
        async with self.lock:
            if sid not in self.connected or not self.server.manager.is_connected(sid, "/"):
                return self.error(409, "Socket is no longer connected.")
            existing = self.memberships.get(sid)
            if existing and (existing.ma_code_phong_thi, existing.user_type, existing.id_hs) != (membership.ma_code_phong_thi, membership.user_type, membership.id_hs):
                return self.error(409, "Disconnect before changing socket identity.")
            previous = self._release(sid)
            if previous:
                await self.server.leave_room(sid, previous.ma_code_phong_thi)
                if previous.user_type == "teacher":
                    await self.server.leave_room(sid, self.teacher_room(previous.ma_code_phong_thi))
                if previous.ma_code_phong_thi != membership.ma_code_phong_thi:
                    await self._broadcast_students(previous.ma_code_phong_thi)
            await self.server.enter_room(sid, membership.ma_code_phong_thi)
            if membership.user_type == "teacher":
                await self.server.enter_room(sid, self.teacher_room(membership.ma_code_phong_thi))
            else:
                room = self.students.setdefault(membership.ma_code_phong_thi, {})
                entry = room.setdefault(membership.id_hs, {"student": membership.student, "sids": set()})
                entry["student"] = membership.student
                entry["sids"].add(sid)
            self.memberships[sid] = membership
            if states is not None:
                packet = SocketStudentStatesSchema(ma_code_phong_thi=membership.ma_code_phong_thi, hoc_sinh_list=states)
                await self.server.emit("student_states_updated", packet.model_dump(by_alias=True), to=sid)
            await self._broadcast_students(membership.ma_code_phong_thi)
        logger.info("socket_joined sid=%s room=%s role=%s", sid, membership.ma_code_phong_thi, membership.user_type)
        return SocketJoinAckSchema.model_validate(
            membership.model_dump(exclude={"student"}),
        ).model_dump(by_alias=True)

    def _event_membership(self, sid, code, role=None):
        member = self.memberships.get(sid)
        if sid not in self.connected or not member or member.ma_code_phong_thi != code:
            raise HTTPException(status_code=403, detail="Join this exam room first.")
        if role and member.user_type != role:
            raise HTTPException(status_code=403, detail="This action is not allowed for this role.")
        return member

    async def start_exam(self, sid, data):
        try:
            packet = ExamRoomEventSchema.model_validate(data)
            self._event_membership(sid, packet.ma_code_phong_thi, "teacher")
            auth = await self.server.get_session(sid)
            async with self.flow_locks.setdefault(packet.ma_code_phong_thi, asyncio.Lock()):
                run = await asyncio.to_thread(exam_flow_service.start_exam, packet.ma_code_phong_thi, auth.get("token"))
                run = run.model_copy(update={"server_time": exam_flow_service.now_ms()})
                async with self.lock:
                    await self.server.emit("exam_started", run.model_dump(by_alias=True), room=packet.ma_code_phong_thi)
            logger.info("exam_started room=%s run=%s state=DANG_THI", packet.ma_code_phong_thi, run.run_id)
            return ExamSyncSchema(server_time=exam_flow_service.now_ms(), exam=run).model_dump(by_alias=True)
        except ValidationError:
            return self.error(422, "Invalid start payload.")
        except HTTPException as exc:
            return self.error(exc.status_code, str(exc.detail))
        except Exception:
            return self.error(503, "Unable to start the exam. Please retry.")

    async def sync_exam(self, sid, data):
        received = exam_flow_service.now_ms()
        try:
            packet = ExamRoomEventSchema.model_validate(data)
            # Snapshots cannot observe sidecar data prepared before a DB commit.
            async with self.flow_locks.setdefault(packet.ma_code_phong_thi, asyncio.Lock()):
                member = self._event_membership(sid, packet.ma_code_phong_thi)
                auth = await self.server.get_session(sid)
                result = await asyncio.to_thread(exam_flow_service.sync_exam, member, auth.get("token"))
                if member.user_type == "teacher":
                    states = await asyncio.to_thread(exam_recovery_service.read_student_states, member)
                    async with self.lock:
                        packet = SocketStudentStatesSchema(ma_code_phong_thi=member.ma_code_phong_thi, hoc_sinh_list=states)
                        await self.server.emit("student_states_updated", packet.model_dump(by_alias=True), to=sid)
                else:
                    state = await asyncio.to_thread(exam_recovery_service.read_student_state, member)
                    result = result.model_copy(update={"student_state": state})
                result = result.model_copy(update={"server_time": exam_flow_service.now_ms(), "request_received_at": received})
                return result.model_dump(by_alias=True)
        except ValidationError:
            return self.error(422, "Invalid synchronization payload.")
        except HTTPException as exc:
            return self.error(exc.status_code, str(exc.detail))
        except Exception:
            return self.error(503, "Exam synchronization unavailable.")

    async def submit_exam(self, sid, data):
        try:
            packet = ExamSubmitSchema.model_validate(data)
            member = self._event_membership(sid, packet.ma_code_phong_thi, "student")
            auth = await self.server.get_session(sid)
            outcome = await grading_service.submit_socket_and_notify(member, auth.get("token"), packet, self)
            return {"status": "ok", "submission": outcome.receipt.model_dump(by_alias=True), "result": outcome.result.model_dump(by_alias=True)}
        except ValidationError:
            return self.error(422, "Invalid submission payload.")
        except HTTPException as exc:
            return self.error(exc.status_code, str(exc.detail))
        except Exception:
            return self.error(503, "Unable to receive the submission. Please retry.")

    async def disconnect(self, sid, reason=None):
        disconnected_at = exam_flow_service.now_ms()
        async with self.lock:
            self.connected.discard(sid)
            member = self.memberships.get(sid)
        if member:
            # Serialize with joins, starts and reconnects in this room. Other rooms remain free.
            async with self.flow_locks.setdefault(member.ma_code_phong_thi, asyncio.Lock()):
                async with self.lock:
                    previous = self._release(sid)
                    entry = self.students.get(member.ma_code_phong_thi, {}).get(member.id_hs)
                    last_socket = not entry or not (entry["sids"] & self.connected)
                if previous and previous.user_type == "student" and last_socket:
                    try:
                        state = await asyncio.to_thread(exam_recovery_service.mark_disconnected, previous, disconnected_at)
                        if state:
                            async with self.lock:
                                await self.server.emit("student_disconnected", state.model_dump(by_alias=True), room=self.teacher_room(previous.ma_code_phong_thi))
                            logger.info("student_disconnected student=%s room=%s state=MAT_KET_NOI", previous.id_hs, previous.ma_code_phong_thi)
                    except Exception:
                        logger.error("student_disconnect_persist_failed student=%s room=%s", previous.id_hs, previous.ma_code_phong_thi)
                        packet = StudentRecoveryErrorSchema(status_code=503, detail="Unable to persist disconnected student state.", ma_code_phong_thi=previous.ma_code_phong_thi, id_hs=previous.id_hs)
                        async with self.lock:
                            await self.server.emit("student_connection_error", packet.model_dump(by_alias=True), room=self.teacher_room(previous.ma_code_phong_thi))
                async with self.lock:
                    await self._broadcast_students(member.ma_code_phong_thi)
        logger.info("socket_disconnected sid=%s", sid)

    async def reconnect_exam(self, sid, data):
        received = exam_flow_service.now_ms()
        try:
            packet = ReconnectExamSchema.model_validate(data)
            async with self.flow_locks.setdefault(packet.ma_code_phong_thi, asyncio.Lock()):
                member = self._event_membership(sid, packet.ma_code_phong_thi, "student")
                if member.id_hs != packet.id_hs:
                    return self.error(403, "Student identity does not match this socket.")
                auth = await self.server.get_session(sid)
                result = await asyncio.to_thread(exam_recovery_service.reconnect_exam, member, auth.get("token"))
                if sid not in self.connected:
                    return self.error(409, "Socket is no longer connected.")
                state = result.student_state
                async with self.lock:
                    await self.server.emit("student_reconnected", state.model_dump(by_alias=True), room=self.teacher_room(member.ma_code_phong_thi))
                result = result.model_copy(update={"server_time": exam_flow_service.now_ms(), "request_received_at": received})
                logger.info("student_reconnected student=%s room=%s state=%s remaining=%s", member.id_hs, member.ma_code_phong_thi, state.trang_thai, result.remaining_seconds)
                return result.model_dump(by_alias=True)
        except ValidationError:
            return self.error(422, "Invalid reconnect payload.")
        except HTTPException as exc:
            return self.error(exc.status_code, str(exc.detail))
        except Exception:
            return self.error(503, "Unable to recover the exam state. Please retry.")

    async def close_exam_room(self, sid, data):
        try:
            packet = ExamRoomEventSchema.model_validate(data)
            async with self.flow_locks.setdefault(packet.ma_code_phong_thi, asyncio.Lock()):
                self._event_membership(sid, packet.ma_code_phong_thi, "teacher")
                auth = await self.server.get_session(sid)
                closed = await asyncio.to_thread(room_lifecycle_service.close_room, packet.ma_code_phong_thi, auth.get("token"))
                async with self.lock:
                    await self.server.emit("room_closed", closed.model_dump(by_alias=True), room=packet.ma_code_phong_thi)
                return {"status": "ok", **closed.model_dump(by_alias=True)}
        except ValidationError:
            return self.error(422, "Invalid room close payload.")
        except HTTPException as exc:
            return self.error(exc.status_code, str(exc.detail))
        except Exception:
            return self.error(503, "Unable to close the room. Please retry.")

    async def shutdown(self):
        await self.server.shutdown()
        self.connected.clear()
        self.memberships.clear()
        self.students.clear()
        self.flow_locks.clear()


socket_manager = ExamSocketManager(sio)
