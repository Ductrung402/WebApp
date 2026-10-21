import { test, expect } from './fixtures/artifacts.js'

const tokenKey = 'quizAi.teacherAccessToken'
const teacher = { id_gv: 'teacher-test-id', email: 'teacher@example.com', ho_ten: 'Nguyễn Văn A', truong: 'THPT A', tinh: 'TP HCM' }
const headers = {
  'access-control-allow-origin': 'http://localhost:5173',
  'access-control-allow-methods': 'GET, POST, OPTIONS',
  'access-control-allow-headers': 'authorization,content-type',
}

async function respond(route, status, body) {
  await route.fulfill({ status, headers, contentType: 'application/json', body: JSON.stringify(body) })
}

async function mockApi(page) {
  await page.route('**/api/auth/**', async (route) => {
    if (route.request().method() === 'OPTIONS') return route.fulfill({ status: 204, headers })
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/login')) {
      expect(route.request().headers().authorization).toBeUndefined()
      const payload = route.request().postDataJSON()
      if (payload.mat_khau === 'TeacherPass123!') return respond(route, 200, { access_token: 'valid-test-token', token_type: 'bearer' })
      return respond(route, 401, { detail: 'Incorrect email or password.' })
    }
    if (path.endsWith('/register')) return respond(route, 201, teacher)
    if (route.request().headers().authorization === 'Bearer valid-test-token') return respond(route, 200, teacher)
    return respond(route, 401, { detail: 'Invalid or expired credentials.' })
  })
}

async function fillLogin(page, password = 'TeacherPass123!') {
  await page.getByLabel('Email', { exact: true }).fill('teacher@example.com')
  await page.getByLabel('Mật khẩu', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
}

async function fillRegister(page) {
  await page.getByLabel('Họ và tên').fill('Nguyễn Văn A')
  await page.getByLabel('Email', { exact: true }).fill('teacher@example.com')
  await page.getByLabel('Mật khẩu', { exact: true }).fill('TeacherPass123!')
}

test.beforeEach(async ({ page }) => { await mockApi(page) })

test('anonymous dashboard access redirects to login', async ({ page }) => {
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { name: 'Đăng nhập', exact: true })).toBeVisible()
})

test('login sends backend payload and Bearer header, restores session and logs out', async ({ page }) => {
  const consoleErrors = []
  page.on('pageerror', (error) => consoleErrors.push(error.message))
  await page.goto('/login')
  const loginRequest = page.waitForRequest((request) => request.url().endsWith('/api/auth/login') && request.method() === 'POST')
  await fillLogin(page)
  expect((await loginRequest).postDataJSON()).toEqual({ email: 'teacher@example.com', mat_khau: 'TeacherPass123!' })
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.getByRole('heading', { name: 'Hồ sơ giáo viên', level: 1 })).toBeVisible()
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBe('valid-test-token')
  expect(await page.evaluate(() => JSON.stringify(localStorage))).not.toContain('TeacherPass123!')
  const meRequest = page.waitForRequest((request) => request.url().endsWith('/api/auth/me') && request.method() === 'GET')
  await page.reload()
  expect((await meRequest).headers().authorization).toBe('Bearer valid-test-token')
  await expect(page.getByRole('heading', { name: 'Thông tin tài khoản' })).toBeVisible()
  await page.goto('/register')
  await expect(page).toHaveURL(/\/dashboard$/)
  await page.getByRole('button', { name: 'Đăng xuất' }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
  expect(consoleErrors).toEqual([])
})

test('invalid email, blank name and overlong UTF-8 password do not reach API', async ({ page }) => {
  const requests = []
  page.on('request', (request) => { if (request.url().includes('/api/auth/')) requests.push(request.url()) })
  await page.goto('/register')
  await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  await expect(page.getByText('Vui lòng nhập họ và tên.')).toBeVisible()
  await expect(page.getByText('Vui lòng nhập địa chỉ email hợp lệ.')).toBeVisible()
  await expect(page.getByText('Mật khẩu cần ít nhất 8 ký tự.')).toBeVisible()
  await fillRegister(page)
  await page.getByLabel('Mật khẩu', { exact: true }).fill('é'.repeat(37))
  await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  await expect(page.getByText('Mật khẩu tối đa 72 byte và không chứa ký tự null.')).toBeVisible()
  expect(requests).toEqual([])
})

test('registration sends all fields and redirects to login without storing JWT', async ({ page }) => {
  await page.goto('/register')
  await fillRegister(page)
  await page.getByLabel('Trường (không bắt buộc)', { exact: true }).fill(' THPT A ')
  await page.getByLabel('Tỉnh / Thành phố (không bắt buộc)', { exact: true }).fill(' TP HCM ')
  const request = page.waitForRequest((request) => request.url().endsWith('/api/auth/register') && request.method() === 'POST')
  await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  expect((await request).postDataJSON()).toEqual({ email: 'teacher@example.com', mat_khau: 'TeacherPass123!', ho_ten: 'Nguyễn Văn A', truong: 'THPT A', tinh: 'TP HCM' })
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('status')).toContainText('Tài khoản đã được tạo.')
  await expect(page.getByLabel('Email', { exact: true })).toHaveValue('teacher@example.com')
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
})

test('empty optional school and province become null', async ({ page }) => {
  await page.goto('/register')
  await fillRegister(page)
  const request = page.waitForRequest((request) => request.url().endsWith('/api/auth/register') && request.method() === 'POST')
  await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  expect((await request).postDataJSON()).toMatchObject({ truong: null, tinh: null })
  await expect(page).toHaveURL(/\/login$/)
})

test('wrong credentials display an error and never store a token', async ({ page }) => {
  await page.goto('/login')
  await fillLogin(page, 'WrongPass123!')
  await expect(page.getByRole('alert')).toContainText('Email hoặc mật khẩu không chính xác.')
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
})

test('duplicate email displays conflict feedback', async ({ page }) => {
  await page.route('**/api/auth/register', (route) => respond(route, 409, { detail: 'Email already registered.' }))
  await page.goto('/register')
  await fillRegister(page)
  await page.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Email này đã được đăng ký.')
  await expect(page).toHaveURL(/\/register$/)
})

test('expired token is rejected before dashboard is shown', async ({ page }) => {
  await page.addInitScript((key) => localStorage.setItem(key, 'expired-test-token'), tokenKey)
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('alert')).toContainText('Phiên đăng nhập đã hết hạn.')
  await expect(page.getByRole('heading', { name: 'Thông tin tài khoản' })).toHaveCount(0)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
})

test('temporary server failure preserves token and allows retry', async ({ page }) => {
  let unavailable = true
  await page.addInitScript((key) => localStorage.setItem(key, 'valid-test-token'), tokenKey)
  await page.route('**/api/auth/me', (route) => respond(route, unavailable ? 503 : 200, unavailable ? { detail: 'Unavailable' } : teacher))
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: 'Chưa thể xác minh phiên đăng nhập' })).toBeVisible()
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBe('valid-test-token')
  unavailable = false
  await page.getByRole('button', { name: 'Thử lại' }).click()
  await expect(page.getByRole('heading', { name: 'Thông tin tài khoản' })).toBeVisible()
})

test('logout in another tab updates the current tab', async ({ page, context }) => {
  await page.goto('/login')
  await fillLogin(page)
  await expect(page).toHaveURL(/\/dashboard$/)
  const secondPage = await context.newPage()
  await mockApi(secondPage)
  await secondPage.goto('/dashboard')
  await expect(secondPage.getByRole('heading', { name: 'Thông tin tài khoản' })).toBeVisible()
  await secondPage.getByRole('button', { name: 'Đăng xuất' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await secondPage.close()
})

test('desktop and mobile forms render without horizontal overflow', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Đăng nhập', exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('login-desktop.png'), fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/register')
  await expect(page.getByRole('button', { name: 'Tạo tài khoản', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: testInfo.outputPath('register-mobile.png'), fullPage: true })
})
