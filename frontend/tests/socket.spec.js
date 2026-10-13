import { test, expect } from '@playwright/test'

const code = 'AB12CD'
const room = { idPhongThi: 'room-one', maCodePhongThi: code, tenLop: '6A', thoiGianLamBai: 30, trangThaiPhong: 'DANG_CHO', ngayTaoPhong: '2026-10-13', hocSinhList: [] }
const student = { idHs: 'student-one', hoVaTen: 'Nguyễn Văn An', maCodePhongThi: code }
const cors = { 'access-control-allow-origin': 'http://localhost:5173', 'access-control-allow-methods': 'GET, POST, OPTIONS', 'access-control-allow-headers': 'authorization,content-type' }

async function fixtures(page) {
  await page.route('**/api/**', (route) => {
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const path = new URL(route.request().url()).pathname
    const response = path === '/api/auth/me'
      ? { id_gv: 'teacher-test-id', email: 'teacher@example.com', ho_ten: 'Teacher', truong: null, tinh: null }
      : path === '/api/exams' ? [] : room
    return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify(response) })
  })
}

async function mockSocket(page, options = {}) {
  const state = { sockets: [], joins: [], auth: [], statusCode: null, ...options }
  await page.routeWebSocket('**/socket.io/**', (ws) => {
    state.sockets.push(ws)
    ws.onMessage((message) => {
      const text = String(message)
      if (text.startsWith('40')) {
        state.auth.push(text.length > 2 ? JSON.parse(text.slice(2)) : {})
        ws.send('40' + JSON.stringify({ sid: `socket-${state.sockets.length}` }))
      } else if (text === '2') ws.send('3')
      else if (text.startsWith('42')) {
        const index = text.indexOf('[')
        const ackId = text.slice(2, index)
        const [event, payload] = JSON.parse(text.slice(index))
        expect(['join_exam_room', 'sync_exam', 'reconnect_exam']).toContain(event)
        if (event === 'join_exam_room') state.joins.push(payload)
        const ack = ['sync_exam', 'reconnect_exam'].includes(event) ? { status: 'ok', serverTime: Date.now(), exam: null, submission: null, studentState: { ...student, idPhongThi: 'room-one', trangThai: 'DANG_THI', stateVersion: 0, stateChangedAt: Date.now() } } : state.statusCode ? { status: 'error', statusCode: state.statusCode, detail: 'Denied' }
          : { status: 'ok', ...payload, idPhongThi: 'room-one' }
        ws.send('43' + ackId + JSON.stringify([ack]))
      }
    })
    ws.send('0' + JSON.stringify({ sid: 'engine-sid', upgrades: [], pingInterval: 25000, pingTimeout: 20000, maxPayload: 1000000 }))
  })
  state.emitRoster = (packet) => state.sockets.at(-1).send('42' + JSON.stringify(['room_students_updated', packet]))
  return state
}

test('teacher socket joins with JWT and roster push updates UI before the next poll', async ({ page }) => {
  await page.clock.install()
  await page.addInitScript(() => localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token'))
  await fixtures(page)
  const state = await mockSocket(page)
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối phòng chờ')
  expect(state.auth.at(-1)).toEqual({ token: 'valid-test-token' })
  expect(state.joins.at(-1)).toEqual({ maCodePhongThi: code, userType: 'teacher', idHs: null })
  state.emitRoster({ maCodePhongThi: code, hocSinhList: [student] })
  await expect(page.getByText(student.hoVaTen, { exact: true })).toBeVisible()
  state.emitRoster({ maCodePhongThi: code, hocSinhList: [student] })
  await expect(page.getByText('1 thí sinh', { exact: true })).toBeVisible()
  // An older REST snapshot must not remove a student already confirmed by Socket.IO.
  await page.clock.fastForward(3100)
  await expect(page.getByText('1 thí sinh', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bắt đầu làm bài' })).toBeEnabled()
})

test('student socket uses saved identity without teacher JWT and ignores class rosters', async ({ page }) => {
  await page.addInitScript(({ student, code }) => {
    localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token')
    sessionStorage.setItem('quizAi.studentSession', JSON.stringify({ ...student, idPhongThi: 'room-one', tenLop: '6A', thoiGianLamBai: 30 }))
  }, { student, code })
  await fixtures(page)
  const state = await mockSocket(page)
  await page.goto('/exam-room/AB12CD/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối phòng chờ')
  expect(state.auth.at(-1)).toEqual({})
  expect(state.joins.at(-1)).toEqual({ maCodePhongThi: code, userType: 'student', idHs: 'student-one' })
  state.emitRoster({ maCodePhongThi: code, hocSinhList: [{ ...student, hoVaTen: 'Private other student' }] })
  await expect(page.getByText('Private other student')).toHaveCount(0)
  await expect(page.getByRole('status')).toHaveText('Đang đợi giáo viên bắt đầu bài thi...')
})

test('rosters from another room are ignored and leaving waiting disconnects listeners', async ({ page }) => {
  await page.clock.install()
  await page.addInitScript(() => localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token'))
  await fixtures(page)
  const state = await mockSocket(page)
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối phòng chờ')
  state.emitRoster({ maCodePhongThi: 'QR34ST', hocSinhList: [{ ...student, maCodePhongThi: 'QR34ST' }] })
  await expect(page.getByText(student.hoVaTen, { exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Tạo phòng khác' }).click()
  await expect(page.getByRole('heading', { name: 'Khởi tạo phòng thi' })).toBeVisible()
  const count = state.sockets.length
  await page.clock.fastForward(10000)
  expect(state.sockets).toHaveLength(count)
})

test('reconnection rejoins the room without accumulating roster listeners', async ({ page }) => {
  await page.clock.install()
  await page.addInitScript(() => localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token'))
  await fixtures(page)
  const state = await mockSocket(page)
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối phòng chờ')
  const count = state.joins.length
  state.sockets.at(-1).close({ code: 1001, reason: 'verification restart' })
  await expect(page.getByTestId('socket-status')).toContainText('Đang kết nối lại')
  await page.clock.fastForward(6000)
  await expect.poll(() => state.joins.length).toBeGreaterThan(count)
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối phòng chờ')
  state.emitRoster({ maCodePhongThi: code, hocSinhList: [student] })
  await expect(page.getByText('1 thí sinh', { exact: true })).toBeVisible()
})

test('join denial can be retried without recreating an anonymous student', async ({ page }) => {
  await page.addInitScript((student) => sessionStorage.setItem('quizAi.studentSession', JSON.stringify({ ...student, idPhongThi: 'room-one', tenLop: '6A', thoiGianLamBai: 30 })), student)
  await fixtures(page)
  const state = await mockSocket(page, { statusCode: 403 })
  await page.goto('/exam-room/AB12CD/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Chưa thể xác nhận quyền vào phòng')
  state.statusCode = null
  await page.getByRole('button', { name: 'Thử kết nối lại', exact: true }).click()
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối phòng chờ')
  expect(state.joins.at(-1).idHs).toBe('student-one')
})
