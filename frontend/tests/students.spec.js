import { test, expect } from './fixtures/artifacts.js'

const sessionKey = 'quizAi.studentSession'
const teacherTokenKey = 'quizAi.teacherAccessToken'
const student = {
  idHs: 'student-one', hoVaTen: 'Nguyễn Văn An', maCodePhongThi: 'AB12CD',
  idPhongThi: 'room-one', tenLop: 'Lớp 6A', thoiGianLamBai: 30,
}
const cors = {
  'access-control-allow-origin': 'http://localhost:5173',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'authorization,content-type',
}
const respond = (route, status, body) => route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) })

async function mockStudentApi(page, options = {}) {
  const state = { calls: [], authCalls: [], status: 201, joined: { ...student }, ...options }
  await page.route('**/api/auth/**', (route) => {
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    state.authCalls.push(route.request().url())
    return respond(route, options.authStatus ?? 200, {
      id_gv: 'teacher-test-id', email: 'teacher@example.com', ho_ten: 'Nguyễn Văn A', truong: null, tinh: null,
    })
  })
  await page.route('**/api/rooms**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const call = { path: new URL(request.url()).pathname, method: request.method(), payload: request.postData() ? request.postDataJSON() : null }
    state.calls.push(call)
    expect(call.path).toBe('/api/rooms/join')
    expect(call.method).toBe('POST')
    expect(request.headers().authorization).toBeUndefined()
    if (state.gate) await state.gate
    return respond(route, state.status, state.status === 201 ? state.joined : { detail: 'Admission failed' })
  })
  return state
}

async function fillJoin(page, name = '  Nguyễn Văn An  ', code = 'ab12cd') {
  await page.getByLabel('Họ và tên', { exact: true }).fill(name)
  await page.getByLabel('Mã phòng thi', { exact: true }).fill(code)
}

async function submitJoin(page) {
  await fillJoin(page)
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
}

test('root and join are public without authentication or password fields', async ({ page }) => {
  const state = await mockStudentApi(page)
  for (const path of ['/', '/join']) {
    await page.goto(path)
    await expect(page.getByRole('heading', { name: 'Tham gia phòng thi', exact: true })).toBeVisible()
    await expect(page.getByLabel('Họ và tên', { exact: true })).toBeVisible()
    await expect(page.getByLabel('Mã phòng thi', { exact: true })).toBeVisible()
    await expect(page.locator('input[type="password"], input[type="email"]')).toHaveCount(0)
    await expect(page.getByText('Không cần tài khoản hoặc mật khẩu')).toBeVisible()
  }
  expect(state.authCalls).toHaveLength(0)
  expect(state.calls).toHaveLength(0)
})

test('anonymous join sends only name/code, blocks duplicates and stores the student before navigation', async ({ page }) => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await mockStudentApi(page, { gate })
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/join')
  await fillJoin(page)
  await expect(page.getByLabel('Mã phòng thi', { exact: true })).toHaveValue('AB12CD')
  await page.screenshot({ path: '../docs/screenshots/task_3_3/join-desktop.png', fullPage: true })
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Đang vào phòng…', exact: true })).toBeDisabled()
  await expect(page.getByLabel('Họ và tên', { exact: true })).toBeDisabled()
  await expect(page.getByRole('status')).toContainText('Đang kiểm tra mã')
  await page.locator('form').evaluate((form) => form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })))
  expect(state.calls).toHaveLength(1)
  expect(await page.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
  release()
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
  await expect(page.getByText('Nguyễn Văn An', { exact: true })).toBeVisible()
  await expect(page.getByText('Lớp 6A', { exact: true })).toBeVisible()
  await expect(page.getByText('30 phút', { exact: true })).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('Đang đợi giáo viên bắt đầu bài thi...')
  await expect(page.locator('.animate-pulse')).toBeVisible()
  expect(state.calls[0].payload).toEqual({ hoVaTen: 'Nguyễn Văn An', maCodePhongThi: 'AB12CD' })
  expect(await page.evaluate((key) => JSON.parse(sessionStorage.getItem(key)), sessionKey)).toEqual(student)
  expect(await page.evaluate((key) => localStorage.getItem(key), sessionKey)).toBeNull()
  await page.screenshot({ path: '../docs/screenshots/task_3_3/waiting-desktop.png', fullPage: true })
  expect(state.authCalls).toHaveLength(0)
  expect(errors).toEqual([])
})

test('blank names and invalid codes never reach the API', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page.getByText('Vui lòng nhập họ và tên.')).toBeVisible()
  await expect(page.getByText('Mã phòng gồm đúng 6 ký tự chữ in hoa hoặc số.')).toBeVisible()
  for (const code of ['ABC12', 'AB12!D', 'ĐB12CD', '']) {
    await fillJoin(page, 'An', code)
    await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
    await expect(page.getByText('Mã phòng gồm đúng 6 ký tự chữ in hoa hoặc số.')).toBeVisible()
  }
  await fillJoin(page, '   ', 'AB12CD')
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page.getByText('Vui lòng nhập họ và tên.')).toBeVisible()
  expect(state.calls).toHaveLength(0)
})

test('missing, closed, invalid and unavailable room errors preserve entered values without saving a session', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await fillJoin(page)
  for (const [status, message] of [[404, 'Mã phòng không tồn tại'], [409, 'Phòng thi đã đóng'], [422, 'Thông tin chưa hợp lệ'], [503, 'Máy chủ tạm thời chưa sẵn sàng']]) {
    state.status = status
    await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
    await expect(page.getByRole('alert')).toContainText(message)
    await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue('  Nguyễn Văn An  ')
    await expect(page.getByLabel('Mã phòng thi', { exact: true })).toHaveValue('AB12CD')
    expect(await page.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
    await expect(page).toHaveURL(/\/join$/)
  }
  expect(state.calls).toHaveLength(4)
})

test('correcting a failed join can succeed on explicit retry', async ({ page }) => {
  const state = await mockStudentApi(page, { status: 404 })
  await page.goto('/join')
  await fillJoin(page)
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Mã phòng không tồn tại')
  expect(state.calls).toHaveLength(1)
  state.status = 201
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
  expect(state.calls).toHaveLength(2)
})

test('reload restores identity and room metadata without another join or teacher-only room request', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  await page.reload()
  await expect(page.getByText('Nguyễn Văn An', { exact: true })).toBeVisible()
  await expect(page.getByText('Lớp 6A', { exact: true })).toBeVisible()
  await expect(page.getByText('30 phút', { exact: true })).toBeVisible()
  expect(await page.evaluate((key) => JSON.parse(sessionStorage.getItem(key)).idHs, sessionKey)).toBe('student-one')
  expect(state.calls).toHaveLength(1)
  expect(state.authCalls).toHaveLength(0)
})

test('resume link and submitting the same identity reuse the existing student', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  await page.getByRole('link', { name: 'Trang vào phòng', exact: true }).click()
  await page.getByRole('link', { name: 'Tiếp tục phòng chờ', exact: true }).click()
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
  await page.getByRole('link', { name: 'Trang vào phòng', exact: true }).click()
  await submitJoin(page)
  expect(state.calls).toHaveLength(1)
})

test('missing session redirects to join with the requested code and does not auto-create a student', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.goto('/exam-room/AB12CD/waiting')
  await expect(page).toHaveURL(/\/join$/)
  await expect(page.getByRole('alert')).toContainText('Chưa có phiên học sinh cho phòng này')
  await expect(page.getByLabel('Mã phòng thi', { exact: true })).toHaveValue('AB12CD')
  expect(state.calls).toHaveLength(0)
})

test('corrupt and malformed saved sessions are rejected safely', async ({ page }) => {
  const state = await mockStudentApi(page)
  for (const raw of ['{broken', JSON.stringify({ idHs: 'student-one' }), JSON.stringify({ ...student, thoiGianLamBai: '30' }), JSON.stringify({ ...student, maCodePhongThi: 'abc123' })]) {
    await page.goto('/join')
    await page.evaluate(({ key, raw }) => sessionStorage.setItem(key, raw), { key: sessionKey, raw })
    await page.goto('/exam-room/AB12CD/waiting')
    await expect(page).toHaveURL(/\/join$/)
    expect(await page.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
  }
  expect(state.calls).toHaveLength(0)
})

test('a session for another room is not shown and a lowercase matching URL is canonicalized', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  await page.goto('/exam-room/ZZ99ZZ/waiting')
  await expect(page).toHaveURL(/\/join$/)
  await expect(page.getByLabel('Mã phòng thi', { exact: true })).toHaveValue('ZZ99ZZ')
  await page.goto('/exam-room/ab12cd/waiting')
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
  await expect(page.getByText('Nguyễn Văn An', { exact: true })).toBeVisible()
  expect(state.calls).toHaveLength(1)
})

test('student routes remain public with a teacher token and join does not send that token', async ({ page }) => {
  await page.addInitScript((key) => localStorage.setItem(key, 'valid-test-token'), teacherTokenKey)
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  expect(state.calls).toHaveLength(1)
  expect(await page.evaluate((key) => localStorage.getItem(key), teacherTokenKey)).toBe('valid-test-token')
})

test('anonymous join authentication error does not invalidate an unrelated teacher login', async ({ page }) => {
  await page.addInitScript((key) => localStorage.setItem(key, 'valid-test-token'), teacherTokenKey)
  const state = await mockStudentApi(page, { status: 401 })
  await page.goto('/join')
  await fillJoin(page)
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Vui lòng liên hệ giáo viên')
  expect(await page.evaluate((key) => localStorage.getItem(key), teacherTokenKey)).toBe('valid-test-token')
  expect(state.calls).toHaveLength(1)
})

test('blocked sessionStorage retains the successful admission in memory with a clear notice', async ({ page }) => {
  await page.addInitScript((key) => {
    const original = Storage.prototype.setItem
    Storage.prototype.setItem = function (name, value) {
      if (name === key) throw new DOMException('Storage blocked', 'QuotaExceededError')
      return original.call(this, name, value)
    }
  }, sessionKey)
  const state = await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  await expect(page.getByRole('alert')).toContainText('trình duyệt không lưu được phiên')
  await expect(page.getByText('Nguyễn Văn An', { exact: true })).toBeVisible()
  expect(state.calls).toHaveLength(1)
  expect(await page.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
  await page.reload()
  await expect(page).toHaveURL(/\/join$/)
  expect(state.calls).toHaveLength(1)
})

test('only anonymous response fields are saved and invalid responses cannot open waiting', async ({ page }) => {
  const state = await mockStudentApi(page, { joined: { ...student, accessToken: 'must-not-be-stored', secret: 'ignored' } })
  await page.goto('/join')
  await submitJoin(page)
  expect(await page.evaluate((key) => JSON.parse(sessionStorage.getItem(key)), sessionKey)).toEqual(student)
  await page.evaluate((key) => sessionStorage.removeItem(key), sessionKey)
  state.joined = { ...student, thoiGianLamBai: '30' }
  await page.goto('/join')
  await fillJoin(page)
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Chưa nhận được thông tin phòng hợp lệ')
  await expect(page).toHaveURL(/\/join$/)
  expect(await page.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
})

test('waiting session stays inside its browser tab', async ({ page, context }) => {
  await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  const otherTab = await context.newPage()
  const state = await mockStudentApi(otherTab)
  await otherTab.goto('/exam-room/AB12CD/waiting')
  await expect(otherTab).toHaveURL(/\/join$/)
  expect(await otherTab.evaluate((key) => sessionStorage.getItem(key), sessionKey)).toBeNull()
  expect(state.calls).toHaveLength(0)
  await otherTab.close()
})

test('mobile landing and waiting show all fields without horizontal overflow, including long names', async ({ page }) => {
  const state = await mockStudentApi(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  await fillJoin(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_3_3/join-mobile.png', fullPage: true })
  await page.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
  await expect(page.getByText('30 phút', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_3_3/waiting-mobile.png', fullPage: true })
  await page.evaluate(({ key, value }) => sessionStorage.setItem(key, JSON.stringify(value)), {
    key: sessionKey, value: { ...student, hoVaTen: 'NguyễnThịHoàngAnh'.repeat(10), tenLop: 'NhómÔnTập'.repeat(10) },
  })
  await page.reload()
  await expect(page.getByText('30 phút', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  expect(state.calls).toHaveLength(1)
})

test('waiting animation respects reduced motion', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await mockStudentApi(page)
  await page.goto('/join')
  await submitJoin(page)
  expect(await page.locator('.animate-pulse').evaluate((element) => getComputedStyle(element).animationName)).toBe('none')
})
