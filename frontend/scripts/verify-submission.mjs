// Real REST auto-grading + private teacher notification. Credentials only via stdin.
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
async function post(path, payload) {
  const response = await fetch(fixture.url + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
  assert.equal(response.status, 200, await response.clone().text())
  return response.json()
}
function safe(value) {
  assert.ok(!JSON.stringify(value).includes('dapAnDung'))
  assert.ok(!JSON.stringify(value).includes('dap_an_dung'))
}

try {
  const teacher = await connect(fixture.token)
  const outsider = await connect(fixture.otherToken)
  const student = await connect()
  const leaked = []
  outsider.on('student_submitted', (packet) => leaked.push(packet))
  student.on('student_submitted', (packet) => leaked.push(packet))
  await request(teacher, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'teacher' })
  await request(outsider, 'join_exam_room', { maCodePhongThi: 'QR34ST', userType: 'teacher' })
  await request(student, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'student', idHs: 'fixture-student' })
  const started = await request(teacher, 'start_exam', { maCodePhongThi: 'AB12CD' })
  const questions = started.exam.cauHoiList
  assert.equal(questions.length, 3)
  const payload = { idHs: 'fixture-student', idPhongThi: 'fixture-teacher-room', danhSachCauTraLoi: questions.map((question, index) => ({ idCauHoi: question.idCauHoi, dapAnChon: ['A', 'B', null][index] })) }
  const notified = once(teacher, 'student_submitted')
  const grade = await post('/api/submissions/submit', payload)
  assert.deepEqual(grade, { idHs: 'fixture-student', diemSo: 6.67, soCauDung: 2, tongSoCau: 3, trangThai: 'HOAN_THANH' })
  const event = await notified
  assert.equal(event.diemSo, grade.diemSo)
  assert.equal(event.trangThai, 'HOAN_THANH')
  assert.equal(event.maCodePhongThi, 'AB12CD')
  safe(grade); safe(event)
  const repeat = await post('/api/submissions/submit', { ...payload, danhSachCauTraLoi: [] })
  assert.deepEqual(repeat, grade)
  const recovered = await request(student, 'reconnect_exam', { maCodePhongThi: 'AB12CD', idHs: 'fixture-student' })
  assert.equal(recovered.studentState.trangThai, 'HOAN_THANH')
  assert.ok(recovered.submission)
  student.disconnect()
  const joinResponse = await fetch(fixture.url + '/api/rooms/join', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hoVaTen: 'Trần Bình', maCodePhongThi: 'AB12CD' }) })
  assert.equal(joinResponse.status, 201)
  const admitted = await joinResponse.json()
  const second = await connect()
  await request(second, 'join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'student', idHs: admitted.idHs })
  const another = once(teacher, 'student_submitted')
  const socketGrade = await request(second, 'submit_exam', { maCodePhongThi: 'AB12CD', runId: started.exam.runId, answers: { [questions[0].idCauHoi]: 'A' }, reason: 'timeout' })
  assert.equal(socketGrade.result.diemSo, 3.33)
  assert.equal(socketGrade.result.trangThai, 'HOAN_THANH')
  assert.equal((await another).idHs, admitted.idHs)
  const retry = await request(second, 'submit_exam', { maCodePhongThi: 'AB12CD', runId: started.exam.runId, answers: {}, reason: 'timeout' })
  assert.equal(retry.result.diemSo, 3.33)
  assert.equal(retry.submission.submissionId, socketGrade.submission.submissionId)
  assert.equal(leaked.length, 0)
  if (process.argv.includes('--leaderboard')) {
    const ownResultUrl = fixture.url + '/api/submissions/result?' + new URLSearchParams({ idHs: 'fixture-student', idPhongThi: 'fixture-teacher-room' })
    const readGrade = await fetch(ownResultUrl)
    assert.equal(readGrade.status, 200)
    assert.deepEqual(await readGrade.json(), grade)
    const roomUrl = fixture.url + '/api/rooms/fixture-teacher-room'
    const roomResponse = await fetch(roomUrl, { headers: { Authorization: `Bearer ${fixture.token}` } })
    assert.equal(roomResponse.status, 200)
    const scores = (await roomResponse.json()).ketQuaList
    assert.equal(scores.length, 2)
    assert.equal(scores.find((row) => row.idHs === 'fixture-student').diemSo, 6.67)
    assert.equal(scores.find((row) => row.idHs === admitted.idHs).diemSo, 3.33)
    const closedEvent = once(second, 'room_closed')
    const closed = await request(teacher, 'close_exam_room', { maCodePhongThi: 'AB12CD' })
    assert.equal((await closedEvent).trangThaiPhong, 'DA_DONG')
    assert.equal(closed.trangThaiPhong, 'DA_DONG')
    const afterClose = await fetch(ownResultUrl)
    assert.equal(afterClose.status, 200)
    assert.deepEqual(await afterClose.json(), grade)
    const closedRoomResponse = await fetch(roomUrl, { headers: { Authorization: `Bearer ${fixture.token}` } })
    const closedRoom = await closedRoomResponse.json()
    assert.equal(closedRoom.trangThaiPhong, 'DA_DONG')
    assert.equal(closedRoom.ketQuaList.length, 2)
    safe(closedRoom)
    const denied = await fetch(fixture.url + '/api/rooms/join', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ hoVaTen: 'Late student', maCodePhongThi: 'AB12CD' }) })
    assert.equal(denied.status, 409)
    log('CLOSE_OUTPUT ' + JSON.stringify(closed))
    log('SCOREBOARD_OUTPUT ' + JSON.stringify(closedRoom))
    log('PASS: actual room score snapshot, own result reload after DA_DONG, close broadcast and new admissions denied.')
  }
  log('SUBMISSION_OUTPUT ' + JSON.stringify(grade))
  log('TEACHER_OUTPUT ' + JSON.stringify(event))
  log('PASS: real REST 2/3 -> 6.67 HOAN_THANH; immutable retry, student_submitted to owner only, completed reconnect, same Socket grading service and no answer-key leakage.')
} finally {
  clients.forEach((client) => client.disconnect())
}
