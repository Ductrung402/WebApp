// Real ERR_02 proof against isolated Uvicorn/SQLite fixtures. Credentials via stdin.
import assert from 'node:assert/strict'
import { io } from 'socket.io-client'

let input = ''
for await (const chunk of process.stdin) input += chunk
const fixture = JSON.parse(input)
const clients = []
const log = (text) => process.stdout.write(text + '\n')
const once = (client, event) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => { client.off(event, listener); reject(new Error(`Missing ${event}`)) }, 5000)
  const listener = (packet) => { clearTimeout(timer); client.off(event, listener); resolve(packet) }
  client.on(event, listener)
})
async function connect(token) {
  const client = io(fixture.url, { autoConnect: false, transports: ['websocket'], reconnection: false, auth: token ? { token } : {} })
  clients.push(client)
  const ready = new Promise((resolve, reject) => { client.once('connect', resolve); client.once('connect_error', reject) })
  client.connect()
  await ready
  return client
}
async function request(client, event, payload) {
  const ack = await client.timeout(5000).emitWithAck(event, payload)
  assert.equal(ack.status, 'ok', JSON.stringify(ack))
  return ack
}
function safe(value) {
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    assert.notEqual(key, 'dap_an_dung')
    assert.notEqual(key, 'dapAnDung')
    safe(child)
  }
}
const join = { maCodePhongThi: 'AB12CD', userType: 'student', idHs: 'fixture-student' }
const recover = { maCodePhongThi: 'AB12CD', idHs: 'fixture-student' }

try {
  const teacher = await connect(fixture.token)
  const outsider = await connect(fixture.otherToken)
  const student = await connect()
  const secondTab = await connect()
  const snapshots = []
  const foreignEvents = []
  const privateLeaks = []
  teacher.on('student_states_updated', (packet) => snapshots.push(packet))
  outsider.on('student_disconnected', (packet) => foreignEvents.push(packet))
  student.on('student_disconnected', (packet) => privateLeaks.push(packet))
  await request(teacher, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'teacher' })
  await request(outsider, 'join_exam_room', { maCodePhongThi: 'QR34ST', userType: 'teacher' })
  await request(student, 'join_exam_room', join)
  await request(secondTab, 'join_exam_room', join)
  const started = await request(teacher, 'start_exam', { maCodePhongThi: 'AB12CD' })
  const run = started.exam
  await request(student, 'reconnect_exam', recover)
  student.disconnect()
  await request(secondTab, 'sync_exam', { maCodePhongThi: 'AB12CD' })
  await request(teacher, 'sync_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(snapshots.at(-1).hocSinhList[0].trangThai, 'DANG_THI')
  const notification = once(teacher, 'student_disconnected')
  // Abruptly terminate the last transport rather than emitting a client-side state request.
  secondTab.io.engine.transport.ws.terminate()
  const disconnected = await notification
  assert.equal(disconnected.idHs, 'fixture-student')
  assert.equal(disconnected.trangThai, 'MAT_KET_NOI')
  assert.equal(disconnected.maCodePhongThi, 'AB12CD')
  assert.ok(disconnected.disconnectedAt > 0)
  assert.ok(disconnected.frozenRemainingSeconds > 0 && disconnected.frozenRemainingSeconds <= 1800)
  await request(teacher, 'sync_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(snapshots.at(-1).hocSinhList[0].trangThai, 'MAT_KET_NOI')
  log('DISCONNECT_OUTPUT ' + JSON.stringify(disconnected))
  const reloadedTeacher = await connect(fixture.token)
  const recoveredRoster = once(reloadedTeacher, 'student_states_updated')
  await request(reloadedTeacher, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'teacher' })
  assert.equal((await recoveredRoster).hocSinhList[0].trangThai, 'MAT_KET_NOI')
  const reconnected = await connect()
  await request(reconnected, 'join_exam_room', join)
  const green = once(teacher, 'student_reconnected')
  const result = await request(reconnected, 'reconnect_exam', recover)
  assert.equal((await green).trangThai, 'DANG_THI')
  assert.equal(result.studentState.trangThai, 'DANG_THI')
  assert.equal(result.exam.runId, run.runId)
  assert.equal(result.exam.endsAt, run.endsAt)
  assert.ok(result.remainingSeconds > 0 && result.remainingSeconds <= disconnected.frozenRemainingSeconds)
  assert.equal(result.examExpired, false)
  safe(result)
  await request(teacher, 'sync_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(snapshots.at(-1).hocSinhList[0].trangThai, 'DANG_THI')
  assert.equal(foreignEvents.length, 0)
  assert.equal(privateLeaks.length, 0)
  log('RECOVERY_OUTPUT ' + JSON.stringify(result))
  log('PASS: last transport disconnect commits MAT_KET_NOI; owner-only notification, teacher reload snapshot, scoped DB states, automatic-ready reconnect DANG_THI, unchanged deadline and no answer-key fields.')
} finally {
  clients.forEach((client) => client.disconnect())
}
