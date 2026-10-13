import { test, expect } from '@playwright/test'

const teacher = { id_gv: 'teacher-test-id', email: 'teacher@example.com', ho_ten: 'Nguyễn Văn A', truong: 'THPT A', tinh: 'TP HCM' }
const exam = { idDeThi: 'exam-one', idGv: teacher.id_gv, tenDeThi: 'Kiến thức về nước', khoi: '6', soLuongCauHoi: 10, ngayTao: '2026-10-13', kieuTaiLieu: 'TEXT' }
const room = { idPhongThi: 'room-one', maCodePhongThi: 'AB12CD', tenLop: 'Lớp 6A', thoiGianLamBai: 30, trangThaiPhong: 'DANG_CHO', ngayTaoPhong: '2026-10-13', hocSinhList: [] }
const students = ['Nguyễn Văn An', 'Trần Minh Anh', 'Lê Hoàng Bảo'].map((hoVaTen, index) => ({ idHs: `student-${index}`, hoVaTen, maCodePhongThi: room.maCodePhongThi }))
const cors = {
  'access-control-allow-origin': 'http://localhost:5173',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'authorization,content-type',
}
const respond = (route, status, body) => route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) })

async function mockRooms(page, options = {}) {
  const state = { exams: [exam], room: structuredClone(room), calls: [], listStatus: 200, createStatus: 201, detailStatus: 200, ...options }
  await page.route('**/api/auth/me', (route) => route.request().method() === 'OPTIONS'
    ? route.fulfill({ status: 204, headers: cors }) : respond(route, 200, teacher))
  await page.route('**/api/exams**', (route) => {
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    expect(route.request().headers().authorization).toBe('Bearer valid-test-token')
    return respond(route, state.listStatus, state.listStatus === 200 ? state.exams : { detail: 'Unavailable' })
  })
  await page.route('**/api/rooms**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const call = { method: request.method(), path: new URL(request.url()).pathname, payload: request.postData() ? request.postDataJSON() : null }
    state.calls.push(call)
    expect(request.headers().authorization).toBe('Bearer valid-test-token')
    if (request.method() === 'POST' && call.path === '/api/rooms') {
      if (state.createGate) await state.createGate
      if (state.createStatus !== 201) return respond(route, state.createStatus, { detail: 'Failed' })
      Object.assign(state.room, { tenLop: call.payload.tenLop, thoiGianLamBai: call.payload.thoiGianLamBai })
      const { hocSinhList, ...created } = state.room
      return respond(route, 201, created)
    }
    if (state.detailGate) await state.detailGate
    return respond(route, state.detailStatus, state.detailStatus === 200 ? state.room : { detail: 'Unavailable or not owned' })
  })
  return state
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token'))
})

async function fillCreate(page) {
  await page.getByLabel('Đề thi', { exact: true }).selectOption('exam-one')
  await page.getByLabel('Tên lớp / nhóm', { exact: true }).fill('  Lớp 6A  ')
  await page.getByLabel('Thời gian làm bài (phút)', { exact: true }).fill('30')
}

test('create sends only schema fields, waits for API, prevents duplicate submission and opens waiting room', async ({ page }) => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await mockRooms(page, { createGate: gate })
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/rooms/create')
  await fillCreate(page)
  await page.screenshot({ path: '../docs/screenshots/task_3_2/create-room.png', fullPage: true })
  await page.getByRole('button', { name: 'Tạo phòng', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Đang tạo phòng…' })).toBeDisabled()
  await expect(page.getByLabel('Tên lớp / nhóm')).toBeDisabled()
  await expect(page.getByRole('status')).toContainText('Đang khởi tạo phòng thi')
  await page.locator('form').evaluate((form) => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })))
  expect(state.calls.filter((call) => call.method === 'POST')).toHaveLength(1)
  release()
  await expect(page).toHaveURL(/\/rooms\/room-one\/waiting$/)
  await expect(page.getByTestId('room-code')).toHaveText('AB12CD')
  await expect(page.getByText('Đang chờ học sinh', { exact: true })).toBeVisible()
  await expect(page.getByText('Chưa có học sinh tham gia', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Bắt đầu làm bài', exact: true })).toBeDisabled()
  expect(state.calls.find((call) => call.method === 'POST').payload).toEqual({ idDeThi: 'exam-one', tenLop: 'Lớp 6A', thoiGianLamBai: 30 })
  await page.screenshot({ path: '../docs/screenshots/task_3_2/waiting-empty.png', fullPage: true })
  expect(errors).toEqual([])
})

test('exam library action preselects the exam in the create form', async ({ page }) => {
  await mockRooms(page)
  await page.goto('/exams')
  await page.getByRole('link', { name: 'Tạo phòng từ đề Kiến thức về nước' }).click()
  await expect(page).toHaveURL(/\/rooms\/create\?examId=exam-one$/)
  await expect(page.getByLabel('Đề thi', { exact: true })).toHaveValue('exam-one')
  await expect(page.getByRole('heading', { name: 'Khởi tạo phòng thi', exact: true })).toBeVisible()
})

test('invalid fields do not call the create API', async ({ page }) => {
  const state = await mockRooms(page)
  await page.goto('/rooms/create')
  await page.getByRole('button', { name: 'Tạo phòng', exact: true }).click()
  await expect(page.getByText('Vui lòng chọn một đề thi có câu hỏi trong kho của bạn.')).toBeVisible()
  await expect(page.getByText('Vui lòng nhập tên lớp hoặc nhóm.')).toBeVisible()
  await fillCreate(page)
  for (const value of ['0', '-1', '2.5', '2147483648', '']) {
    await page.getByLabel('Thời gian làm bài (phút)').fill(value)
    await page.getByRole('button', { name: 'Tạo phòng', exact: true }).click()
    await expect(page.getByText('Thời gian phải là số nguyên phút từ 1 đến 2147483647.')).toBeVisible()
  }
  expect(state.calls).toHaveLength(0)
})

test('empty exam library and list errors offer clear next actions', async ({ page }) => {
  const state = await mockRooms(page, { listStatus: 503, exams: [] })
  await page.goto('/rooms/create')
  await expect(page.getByRole('alert')).toContainText('Máy chủ tạm thời chưa sẵn sàng')
  state.listStatus = 200
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Chưa có đề thi để tạo phòng' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Tạo đề bằng AI', exact: true }).last()).toHaveAttribute('href', '/exams/create-ai')
  expect(state.calls).toHaveLength(0)
})

test('zero-question exams cannot be selected and missing preselected exam is not silently replaced', async ({ page }) => {
  await mockRooms(page, { exams: [{ ...exam, idDeThi: 'empty', soLuongCauHoi: 0 }, exam] })
  await page.goto('/rooms/create?examId=missing')
  await expect(page.getByLabel('Đề thi', { exact: true })).toHaveValue('')
  await expect(page.locator('option[value="empty"]')).toBeDisabled()
})

test('create failure keeps form values and explicit retry can succeed', async ({ page }) => {
  const state = await mockRooms(page, { createStatus: 503 })
  await page.goto('/rooms/create')
  await fillCreate(page)
  await page.getByRole('button', { name: 'Tạo phòng', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Máy chủ tạm thời chưa sẵn sàng')
  await expect(page.getByLabel('Tên lớp / nhóm')).toHaveValue('  Lớp 6A  ')
  await expect(page).toHaveURL(/\/rooms\/create$/)
  expect(state.calls.filter((call) => call.method === 'POST')).toHaveLength(1)
  state.createStatus = 201
  await page.getByRole('button', { name: 'Tạo phòng', exact: true }).click()
  await expect(page).toHaveURL(/\/rooms\/room-one\/waiting$/)
})

test('waiting roster polls for new students without invoking exam start', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page)
  await page.setViewportSize({ width: 1440, height: 1050 })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByText('0 thí sinh', { exact: true })).toBeVisible()
  state.room.hocSinhList = students
  await page.clock.fastForward(3100)
  await expect(page.getByText('3 thí sinh', { exact: true })).toBeVisible()
  for (const student of students) await expect(page.getByText(student.hoVaTen, { exact: true })).toBeVisible()
  await expect(page.getByTestId('room-code')).toHaveText('AB12CD')
  await page.screenshot({ path: '../docs/screenshots/task_3_2/waiting-students.png', fullPage: true })
  await expect(page.getByRole('button', { name: 'Bắt đầu làm bài', exact: true })).toBeDisabled()
  expect(state.calls.every((call) => call.method === 'GET')).toBe(true)
  expect(state.calls.length).toBeGreaterThanOrEqual(2)
})

test('temporary polling failure retains roster and recovers automatically', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page, { room: { ...room, hocSinhList: students } })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByText(students[0].hoVaTen, { exact: true })).toBeVisible()
  state.detailStatus = 503
  await page.clock.fastForward(3100)
  await expect(page.getByRole('alert')).toContainText('Máy chủ tạm thời chưa sẵn sàng')
  await expect(page.getByText(students[0].hoVaTen, { exact: true })).toBeVisible()
  await expect(page.getByText('Đang hiển thị danh sách ở lần cập nhật gần nhất.')).toBeVisible()
  state.detailStatus = 200
  await page.clock.fastForward(3100)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByText('3 thí sinh', { exact: true })).toBeVisible()
})

test('missing room stops polling until manual retry', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page, { detailStatus: 404 })
  await page.goto('/rooms/missing/waiting')
  await expect(page.getByRole('alert')).toContainText('Không tìm thấy phòng thi')
  await page.clock.fastForward(10000)
  expect(state.calls).toHaveLength(1)
  state.detailStatus = 200
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click()
  await expect(page.getByTestId('room-code')).toHaveText('AB12CD')
})

test('loss of room access removes previously displayed students', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page, { room: { ...room, hocSinhList: students } })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByText('3 thí sinh', { exact: true })).toBeVisible()
  state.detailStatus = 404
  await page.clock.fastForward(3100)
  await expect(page.getByRole('alert')).toContainText('Không tìm thấy phòng thi')
  await expect(page.getByText(students[0].hoVaTen, { exact: true })).toHaveCount(0)
  await expect(page.getByTestId('room-code')).toHaveCount(0)
})

test('polling stops on navigation and closed rooms stop polling', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page)
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('room-code')).toBeVisible()
  await page.getByRole('link', { name: 'Tạo phòng khác', exact: true }).click()
  await expect(page.getByLabel('Đề thi', { exact: true })).toBeVisible()
  const count = state.calls.length
  await page.clock.fastForward(10000)
  expect(state.calls).toHaveLength(count)
  state.room.trangThaiPhong = 'DA_DONG'
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByText('Đã đóng', { exact: true })).toBeVisible()
  await expect(page.getByText('Phòng đã đóng. Kết quả đã lưu vẫn được giữ lại.', { exact: true })).toBeVisible()
  const closedCount = state.calls.length
  await page.clock.fastForward(10000)
  expect(state.calls).toHaveLength(closedCount)
})

test('polling does not overlap while a request is pending', async ({ page }) => {
  await page.clock.install()
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await mockRooms(page, { detailGate: gate })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByRole('status', { name: 'Đang tải phòng thi' })).toBeVisible()
  await page.clock.fastForward(6000)
  expect(state.calls).toHaveLength(1)
  release()
  await expect(page.getByTestId('room-code')).toBeVisible()
  await page.clock.fastForward(3100)
  await expect.poll(() => state.calls.length).toBe(2)
})

test('hidden tab pauses polling and resumes when visible', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page)
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('room-code')).toBeVisible()
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: true })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  const count = state.calls.length
  await page.clock.fastForward(10000)
  expect(state.calls).toHaveLength(count)
  state.room.hocSinhList = students
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    document.dispatchEvent(new Event('visibilitychange'))
  })
  await expect(page.getByText('3 thí sinh', { exact: true })).toBeVisible()
})

test('copy button copies the complete six-character room code', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: 'http://localhost:5173' })
  await mockRooms(page)
  await page.goto('/rooms/room-one/waiting')
  await page.getByRole('button', { name: 'Sao chép mã', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('Đã sao chép mã phòng.')
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe('AB12CD')
})

test('401 during roster refresh logs out and hides room data', async ({ page }) => {
  await page.clock.install()
  const state = await mockRooms(page)
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('room-code')).toBeVisible()
  state.detailStatus = 401
  await page.clock.fastForward(3100)
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByTestId('room-code')).toHaveCount(0)
  expect(await page.evaluate(() => localStorage.getItem('quizAi.teacherAccessToken'))).toBeNull()
})

test('anonymous users cannot access either teacher room route', async ({ page }) => {
  await page.addInitScript(() => localStorage.removeItem('quizAi.teacherAccessToken'))
  const state = await mockRooms(page)
  for (const path of ['/rooms/create', '/rooms/room-one/waiting']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/login$/)
  }
  expect(state.calls).toHaveLength(0)
})

test('mobile create form and waiting room display code and long names without overflow', async ({ page }) => {
  await mockRooms(page, { room: { ...room, tenLop: 'Nhóm ôn tập lớp 6A', hocSinhList: [...students, { idHs: 'long-name', hoVaTen: 'NguyễnThịHoàngAnh'.repeat(8), maCodePhongThi: room.maCodePhongThi }] } })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/rooms/create?examId=exam-one')
  await expect(page.getByLabel('Đề thi', { exact: true })).toHaveValue('exam-one')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_3_2/create-mobile.png', fullPage: true })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('room-code')).toHaveText('AB12CD')
  await expect(page.getByText('4 thí sinh', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_3_2/waiting-mobile.png', fullPage: true })
})
