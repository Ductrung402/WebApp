// Real Socket.IO v4 clients. Verification credentials arrive on stdin, never in argv/logs.
import assert from 'node:assert/strict'
import { io } from 'socket.io-client'

let input = ''
for await (const chunk of process.stdin) input += chunk
const fixture = JSON.parse(input)
const clients = []
const logs = []
const log = (message) => { logs.push(message); process.stdout.write(message + '\n') }
const waitFor = (client, event, predicate) => new Promise((resolve, reject) => {
  const timeout = setTimeout(() => { client.off(event, listener); reject(new Error(`Timed out waiting for ${event}`)) }, 5000)
  function listener(packet) {
    if (!predicate(packet)) return
    clearTimeout(timeout)
    client.off(event, listener)
    resolve(packet)
  }
  client.on(event, listener)
})
async function connect(role, token) {
  const client = io(fixture.url, { autoConnect: false, transports: ['websocket'], reconnection: false, auth: token ? { token } : {} })
  clients.push(client)
  const connected = new Promise((resolve, reject) => {
    client.once('connect', resolve)
    client.once('connect_error', () => reject(new Error('WebSocket connection failed')))
  })
  client.connect()
  await connected
  assert.equal(client.io.engine.transport.name, 'websocket')
  log(`CLIENT connected role=${role} sid=${client.id} transport=websocket`)
  return client
}
async function join(client, packet) {
  const ack = await client.timeout(5000).emitWithAck('join_exam_room', packet)
  assert.equal(ack.status, 'ok')
  log(`CLIENT joined room=${ack.maCodePhongThi} role=${ack.userType} status=${ack.status}`)
  return ack
}

try {
  const health = await (await fetch(fixture.url + '/api/health')).json()
  assert.equal(health.status, 'ok')
  const teacher = await connect('teacher', fixture.token)
  const otherTeacher = await connect('other-teacher', fixture.otherToken)
  await join(teacher, { maCodePhongThi: 'AB12CD', userType: 'teacher' })
  await join(otherTeacher, { maCodePhongThi: 'QR34ST', userType: 'teacher' })
  const otherPackets = []
  otherTeacher.on('room_students_updated', (packet) => otherPackets.push(packet))
  const student = await connect('student')
  const studentPackets = []
  student.on('room_students_updated', (packet) => studentPackets.push(packet))
  const roster = waitFor(teacher, 'room_students_updated', (packet) => packet.hocSinhList.some((item) => item.idHs === 'fixture-student'))
  await join(student, { ma_code_phong_thi: 'AB12CD', user_type: 'student', id_hs: 'fixture-student' })
  const packet = await roster
  assert.deepEqual(packet.hocSinhList, [{ idHs: 'fixture-student', hoVaTen: 'Nguyễn Văn An', maCodePhongThi: 'AB12CD' }])
  log(`CLIENT teacher roster room=${packet.maCodePhongThi} students=${packet.hocSinhList.length}`)
  assert.equal(otherPackets.length, 0)
  assert.equal(studentPackets.length, 0)
  const denied = await otherTeacher.timeout(5000).emitWithAck('join_exam_room', { maCodePhongThi: 'AB12CD', userType: 'teacher' })
  assert.equal(denied.statusCode, 403)
  const emptyRoster = waitFor(teacher, 'room_students_updated', (packet) => packet.hocSinhList.length === 0)
  student.disconnect()
  await emptyRoster
  student.connect()
  await new Promise((resolve, reject) => { student.once('connect', resolve); student.once('connect_error', reject) })
  await join(student, { maCodePhongThi: 'AB12CD', userType: 'student', idHs: 'fixture-student' })
  log('PASS: real WebSocket connect/join, teacher-only roster, room isolation, ownership=403 and reconnect/rejoin.')
} finally {
  clients.forEach((client) => client.disconnect())
}
