// Real Socket.IO start/sync/submission proof. Credentials only arrive on stdin.
import assert from 'node:assert/strict'
import { io } from 'socket.io-client'

let input = ''
for await (const chunk of process.stdin) input += chunk
const fixture = JSON.parse(input)
const clients = []
const log = (value) => process.stdout.write(value + '\n')
const once = (client, event) => new Promise((resolve, reject) => {
  const timer = setTimeout(() => { client.off(event, listener); reject(new Error(`No ${event} received`)) }, 5000)
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
async function request(client, event, packet) {
  const response = await client.timeout(5000).emitWithAck(event, packet)
  assert.equal(response.status, 'ok', event)
  return response
}
function noAnswerKey(value) {
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    assert.notEqual(key, 'dap_an_dung')
    assert.notEqual(key, 'dapAnDung')
    noAnswerKey(child)
  }
}

try {
  const teacher = await connect(fixture.token)
  const outsider = await connect(fixture.otherToken)
  const student = await connect()
  await request(teacher, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'teacher' })
  await request(outsider, 'join_exam_room', { maCodePhongThi: 'QR34ST', userType: 'teacher' })
  await request(student, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'student', idHs: 'fixture-student' })
  await request(student, 'reconnect_exam', { maCodePhongThi: 'AB12CD', idHs: 'fixture-student' })
  const waiting = await request(student, 'sync_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(waiting.exam, null)
  const denied = await student.timeout(5000).emitWithAck('start_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(denied.statusCode, 403)
  const unrelated = []
  outsider.on('exam_started', (packet) => unrelated.push(packet))
  const received = once(student, 'exam_started')
  const teacherEvent = once(teacher, 'exam_started')
  const started = await request(teacher, 'start_exam', { maCodePhongThi: 'AB12CD' })
  const run = await received
  assert.equal(run.trangThaiPhong, 'DANG_THI')
  assert.equal(run.endsAt - run.startsAt, run.thoiGianLamBai * 60000)
  assert.equal((await teacherEvent).endsAt, run.endsAt)
  noAnswerKey(run)
  assert.equal(unrelated.length, 0)
  assert.equal(started.exam.runId, run.runId)
  log(`CLIENT exam_started room=${run.maCodePhongThi} state=${run.trangThaiPhong} questions=${run.cauHoiList.length} deadline=${run.endsAt}`)
  const again = await request(teacher, 'start_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(again.exam.runId, run.runId)
  assert.equal(again.exam.endsAt, run.endsAt)
  student.disconnect()
  const reconnected = new Promise((resolve, reject) => { student.once('connect', resolve); student.once('connect_error', reject) })
  student.connect()
  await reconnected
  await request(student, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'student', idHs: 'fixture-student' })
  const recovered = await request(student, 'reconnect_exam', { maCodePhongThi: 'AB12CD', idHs: 'fixture-student' })
  assert.equal(recovered.exam.endsAt, run.endsAt)
  assert.equal(recovered.exam.runId, run.runId)
  noAnswerKey(recovered)
  const answers = { [run.cauHoiList[0].idCauHoi]: 'B' }
  const submit = { maCodePhongThi: 'AB12CD', runId: run.runId, answers, reason: 'manual' }
  const accepted = await request(student, 'submit_exam', submit)
  assert.equal(accepted.submission.idHs, 'fixture-student')
  const duplicate = await request(student, 'submit_exam', submit)
  assert.equal(duplicate.submission.submissionId, accepted.submission.submissionId)
  assert.equal(duplicate.submission.duplicate, true)
  const saved = await request(student, 'sync_exam', { maCodePhongThi: 'AB12CD' })
  assert.equal(saved.submission.submissionId, accepted.submission.submissionId)
  log(`CLIENT submission_received run=${run.runId} duplicate_retry=same_receipt`)
  log('STATE_OUTPUT ' + JSON.stringify(run))
  log('PASS: real teacher start -> student broadcast, DANG_THI, no answer-key fields, same teacher/student deadline, reconnect recovery, duplicate-start prevention and receipt-only submission.')
} finally {
  clients.forEach((client) => client.disconnect())
}
