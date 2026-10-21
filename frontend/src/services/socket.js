import { io } from 'socket.io-client'
import { getAccessToken } from '../utils/tokenStorage.js'
import { createClockAnchor, parsePublicExam } from '../utils/examRun.js'
import { parseSubmissionResult } from '../utils/results.js'

const socketUrl = (import.meta.env.VITE_SOCKET_URL || import.meta.env.VITE_API_BASE_URL || 'http://localhost:8000').replace(/\/+$/, '')
export const socket = io(socketUrl, { autoConnect: false, transports: ['websocket'], reconnection: true, timeout: 10000 })
let releaseCurrent = null

async function examRequest(event, payload) {
  if (!socket.connected) throw new Error('Chưa kết nối với phòng thi. Bài nộp sẽ được giữ để gửi lại.')
  const ack = await socket.timeout(10000).emitWithAck(event, payload)
  if (ack?.status !== 'ok') throw new Error(event === 'start_exam' ? 'Chưa thể bắt đầu bài thi. Hãy kiểm tra phòng và thử lại.' : event === 'close_exam_room' ? 'Chưa thể đóng phòng thi. Vui lòng thử lại.' : 'Máy chủ chưa xác nhận yêu cầu. Vui lòng thử lại.')
  return ack
}

export const startExam = (code) => examRequest('start_exam', { maCodePhongThi: code })
export const submitExam = (code, runId, answers, reason) => examRequest('submit_exam', { maCodePhongThi: code, runId, answers, reason })
export async function closeExamRoom(code) {
  const ack = await examRequest('close_exam_room', { maCodePhongThi: code })
  if (ack.maCodePhongThi !== code || ack.trangThaiPhong !== 'DA_DONG') throw new Error('Chưa nhận được xác nhận đóng phòng hợp lệ.')
  return ack
}

export const reconnectExam = (membership) => examRequest('reconnect_exam', { maCodePhongThi: membership.maCodePhongThi, idHs: membership.idHs })

const validStudentState = (student, code) => student && typeof student.idHs === 'string' && typeof student.hoVaTen === 'string' && student.maCodePhongThi === code && ['DANG_THI', 'MAT_KET_NOI', 'HOAN_THANH'].includes(student.trangThai) && Number.isSafeInteger(student.stateVersion) && student.stateVersion >= 0

async function syncExam(membership, recover = false) {
  const code = membership.maCodePhongThi
  const before = performance.now()
  const packet = recover ? await reconnectExam(membership) : await examRequest('sync_exam', { maCodePhongThi: code })
  const after = performance.now()
  if (!Number.isSafeInteger(packet.serverTime) || packet.serverTime < 0) throw new Error('Chưa đồng bộ được thời gian thi.')
  const exam = packet.exam == null ? null : parsePublicExam(packet.exam)
  if (packet.exam != null && (!exam || exam.maCodePhongThi !== code)) throw new Error('Đề thi nhận được chưa hợp lệ.')
  if (recover && (!validStudentState(packet.studentState, code) || packet.studentState.idHs !== membership.idHs)) throw new Error('Chưa xác nhận được trạng thái bài làm.')
  return { exam, submission: packet.submission || null, studentState: packet.studentState || null, recoveryReady: true, clock: createClockAnchor(packet.serverTime, packet.requestReceivedAt, before, after) }
}

export function joinExamRoom(membership) {
  return new Promise((resolve, reject) => {
    socket.timeout(5000).emit('join_exam_room', {
      maCodePhongThi: membership.maCodePhongThi,
      userType: membership.userType,
      idHs: membership.userType === 'student' ? membership.idHs : null,
    }, (error, ack) => {
      if (error) return reject(new Error('Chưa nhận được xác nhận vào phòng.'))
      if (ack?.status !== 'ok' || ack.maCodePhongThi !== membership.maCodePhongThi) {
        return reject(new Error('Chưa thể xác nhận quyền vào phòng. Hãy kiểm tra phiên tham gia.'))
      }
      resolve(ack)
    })
  })
}

export function subscribeRoomStudents(listener) {
  socket.on('room_students_updated', listener)
  return () => socket.off('room_students_updated', listener)
}

export function connectExamRoom(membership, { onStatus = () => {}, onStudents = () => {}, onSnapshot = () => {}, onClosed = () => {} } = {}) {
  releaseCurrent?.()
  let disposed = false
  let attempt = 0
  let syncTimer
  let syncing = false
  let examRevision = 0
  let needsRecovery = membership.userType === 'student'
  let closed = false
  const synchronize = async () => {
    if (disposed || closed || syncing || !socket.connected) return
    syncing = true
    const revision = examRevision
    const connectionAttempt = attempt
    try {
      const snapshot = await syncExam(membership, needsRecovery)
      if (!disposed && !closed && connectionAttempt === attempt && (revision === examRevision || snapshot.exam)) {
        needsRecovery = false
        onSnapshot(snapshot)
      }
    } catch (error) {
      if (!disposed && !closed && connectionAttempt === attempt) onStatus('error', error.message)
    } finally {
      syncing = false
      if (!disposed && !closed && socket.connected) syncTimer = window.setTimeout(synchronize, revision === examRevision && connectionAttempt === attempt ? 30000 : 0)
    }
  }
  const onExamStarted = (packet) => {
    if (disposed || closed || packet?.maCodePhongThi !== membership.maCodePhongThi) return
    const exam = parsePublicExam(packet)
    if (!exam) { onStatus('error', 'Đề thi nhận được chưa hợp lệ.'); return }
    examRevision += 1
    onSnapshot({ exam, submission: null, recoveryReady: !needsRecovery, clock: { serverNow: exam.serverTime, monotonicAt: performance.now() } })
    window.clearTimeout(syncTimer)
    synchronize()
  }
  const onVisible = () => {
    if (!document.hidden) { window.clearTimeout(syncTimer); synchronize() }
  }
  const onConnect = async () => {
    const current = ++attempt
    needsRecovery = membership.userType === 'student'
    onStatus('joining')
    try {
      await joinExamRoom(membership)
      if (!disposed && current === attempt) { onStatus(needsRecovery ? 'recovering' : 'joined'); await synchronize() }
    } catch (error) {
      if (!disposed && current === attempt) onStatus('error', error.message)
    }
  }
  const onDisconnect = () => { attempt += 1; needsRecovery = membership.userType === 'student'; window.clearTimeout(syncTimer); if (!disposed) onStatus('disconnected') }
  const onConnectError = () => { if (!disposed) onStatus('disconnected') }
  const unsubscribe = subscribeRoomStudents((packet) => {
    if (disposed || membership.userType !== 'teacher' || packet?.maCodePhongThi !== membership.maCodePhongThi || !Array.isArray(packet.hocSinhList)) return
    if (!packet.hocSinhList.every((student) => typeof student.idHs === 'string' && typeof student.hoVaTen === 'string' && student.maCodePhongThi === membership.maCodePhongThi)) return
    onStudents(packet)
  })
  const onStudentStates = (packet) => {
    if (disposed || membership.userType !== 'teacher' || packet?.maCodePhongThi !== membership.maCodePhongThi || !Array.isArray(packet.hocSinhList)) return
    if (!packet.hocSinhList.every((student) => validStudentState(student, membership.maCodePhongThi))) return
    onStudents(packet)
  }
  const onStudentState = (packet) => onStudentStates({ maCodePhongThi: packet?.maCodePhongThi, hocSinhList: [packet] })
  const onRecoveryError = (packet) => {
    if (!disposed && membership.userType === 'teacher' && packet?.maCodePhongThi === membership.maCodePhongThi) onStatus('error', 'Máy chủ chưa lưu được trạng thái kết nối của thí sinh. Vui lòng kiểm tra lại.')
  }
  const onSubmitted = (packet) => {
    if (disposed || membership.userType !== 'teacher' || packet?.maCodePhongThi !== membership.maCodePhongThi || typeof packet.hoVaTen !== 'string') return
    const result = parseSubmissionResult(packet)
    if (!result) return
    onStudents({ maCodePhongThi: membership.maCodePhongThi, hocSinhList: [{ ...result, hoVaTen: packet.hoVaTen, maCodePhongThi: membership.maCodePhongThi }] })
  }
  const onRoomClosed = (packet) => {
    if (disposed || packet?.maCodePhongThi !== membership.maCodePhongThi || packet.trangThaiPhong !== 'DA_DONG' || typeof packet.idPhongThi !== 'string') return
    closed = true
    window.clearTimeout(syncTimer)
    onStatus('closed')
    onSnapshot({ closed: true, recoveryReady: false })
    onClosed(packet)
  }
  socket.on('student_submitted', onSubmitted)
  socket.on('room_closed', onRoomClosed)
  socket.on('student_disconnected', onStudentState)
  socket.on('student_reconnected', onStudentState)
  socket.on('student_states_updated', onStudentStates)
  socket.on('student_connection_error', onRecoveryError)
  socket.on('connect', onConnect)
  socket.on('disconnect', onDisconnect)
  socket.on('connect_error', onConnectError)
  socket.on('exam_started', onExamStarted)
  document.addEventListener('visibilitychange', onVisible)
  socket.auth = membership.userType === 'teacher' ? { token: getAccessToken() } : {}
  onStatus('connecting')
  // StrictMode immediately cleans up its first effect. Open the transport only
  // once that setup survives, rather than opening and aborting a WebSocket.
  queueMicrotask(() => { if (!disposed) socket.connect() })
  const release = () => {
    if (disposed) return
    disposed = true
    attempt += 1
    unsubscribe()
    window.clearTimeout(syncTimer)
    document.removeEventListener('visibilitychange', onVisible)
    socket.off('exam_started', onExamStarted)
    socket.off('student_disconnected', onStudentState)
    socket.off('student_reconnected', onStudentState)
    socket.off('student_states_updated', onStudentStates)
    socket.off('student_connection_error', onRecoveryError)
    socket.off('student_submitted', onSubmitted)
    socket.off('room_closed', onRoomClosed)
    socket.off('connect', onConnect)
    socket.off('disconnect', onDisconnect)
    socket.off('connect_error', onConnectError)
    socket.disconnect()
    socket.auth = {}
    if (releaseCurrent === release) releaseCurrent = null
  }
  releaseCurrent = release
  return release
}
