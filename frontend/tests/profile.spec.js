import { test, expect } from './fixtures/artifacts.js'

const tokenKey = 'quizAi.teacherAccessToken'
const original = { id_gv: 'profile-teacher', email: 'profile@example.com', ho_ten: 'Nguyễn Văn A', truong: 'THPT A', tinh: 'TP HCM' }
const cors = { 'access-control-allow-origin': 'http://localhost:5173', 'access-control-allow-methods': 'GET, PUT, OPTIONS', 'access-control-allow-headers': 'authorization,content-type' }

async function setup(page, options = {}) {
  const state = { teacher: { ...original }, calls: [], status: 200, ...options }
  await page.addInitScript((key) => localStorage.setItem(key, 'profile-test-token'), tokenKey)
  await page.route('**/api/auth/**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    expect(request.headers().authorization).toBe('Bearer profile-test-token')
    const path = new URL(request.url()).pathname
    let status = 200, body = state.teacher
    if (path === '/api/auth/profile') {
      expect(request.method()).toBe('PUT')
      const payload = request.postDataJSON()
      state.calls.push(payload)
      if (state.gate) await state.gate
      status = state.status
      if (status === 200) state.teacher = { ...state.teacher, ...payload }
      body = status === 200 ? state.response || state.teacher : { detail: 'Profile update failed' }
    } else expect(path).toBe('/api/auth/me')
    await route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: 'Hồ sơ giáo viên' })).toBeVisible()
  return state
}

async function edit(page, name = '  Nguyễn Thị B  ') {
  await page.getByRole('button', { name: 'Chỉnh sửa thông tin' }).click()
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue(original.ho_ten)
  await page.getByLabel('Họ và tên', { exact: true }).fill(name)
  await page.getByLabel('Trường công tác').fill('  THCS B  ')
  await page.getByLabel('Tỉnh / Thành phố', { exact: true }).fill('  Hà Nội  ')
}

test('profile saves with Bearer, updates header immediately and survives reload without logging in again', async ({ page }) => {
  const state = await setup(page)
  await edit(page)
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.getByRole('status')).toContainText('Đã cập nhật thông tin cá nhân.')
  expect(state.calls).toEqual([{ ho_ten: 'Nguyễn Thị B', truong: 'THCS B', tinh: 'Hà Nội' }])
  await expect(page.locator('header')).toContainText('Nguyễn Thị B')
  await expect(page.locator('main dl')).toContainText('Nguyễn Thị B')
  await expect(page.locator('main dl')).toContainText('THCS B')
  await expect(page.locator('main dl')).toContainText('Hà Nội')
  await expect(page.locator('main dl')).toContainText(original.email)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBe('profile-test-token')
  await page.reload()
  await expect(page.locator('header')).toContainText('Nguyễn Thị B')
  await page.getByRole('button', { name: 'Chỉnh sửa thông tin' }).click()
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue('Nguyễn Thị B')
})

test('blank name is rejected locally and optional school/province can be cleared', async ({ page }) => {
  const state = await setup(page)
  await edit(page, '   ')
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.getByText('Vui lòng nhập họ và tên.')).toBeVisible()
  expect(state.calls).toHaveLength(0)
  await page.getByLabel('Họ và tên', { exact: true }).fill('Nguyễn Thị B')
  await page.getByLabel('Trường công tác').fill('  ')
  await page.getByLabel('Tỉnh / Thành phố', { exact: true }).fill('')
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.getByRole('status')).toContainText('Đã cập nhật')
  expect(state.calls).toEqual([{ ho_ten: 'Nguyễn Thị B', truong: null, tinh: null }])
  await expect(page.getByText('Chưa cung cấp', { exact: true })).toHaveCount(2)
})

test('saving disables fields and actions until server confirmation, without duplicate requests', async ({ page }) => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await setup(page, { gate })
  try {
    await edit(page)
    await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
    await expect(page.getByRole('button', { name: 'Đang lưu…' })).toBeDisabled()
    await expect(page.getByLabel('Họ và tên', { exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Hủy', exact: true })).toBeDisabled()
    await expect(page.locator('header')).toContainText(original.ho_ten)
    await expect.poll(() => state.calls.length).toBe(1)
  } finally { release() }
  await expect(page.getByRole('status')).toContainText('Đã cập nhật')
  expect(state.calls).toHaveLength(1)
})

test('failed save keeps edits and original header until an explicit retry succeeds', async ({ page }) => {
  const state = await setup(page, { status: 503 })
  await edit(page)
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.getByRole('alert')).toContainText('Máy chủ đang gặp sự cố')
  await expect(page.getByLabel('Trường công tác')).toHaveValue('  THCS B  ')
  await expect(page.locator('header')).toContainText(original.ho_ten)
  state.status = 200
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.locator('header')).toContainText('Nguyễn Thị B')
  expect(state.calls).toHaveLength(2)
})

test('expired JWT on save logs out rather than displaying a successful profile update', async ({ page }) => {
  await setup(page, { status: 401 })
  await edit(page)
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
  await expect(page.getByText('Đã cập nhật thông tin cá nhân.')).toHaveCount(0)
})

test('mobile profile editing has no overflow; cancelling discards unsaved changes', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const state = await setup(page)
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await edit(page)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_6_4/profile-mobile.png', fullPage: true })
  await page.getByRole('button', { name: 'Hủy', exact: true }).click()
  expect(state.calls).toHaveLength(0)
  await expect(page.locator('main dl')).toContainText(original.ho_ten)
  await page.getByRole('button', { name: 'Chỉnh sửa thông tin' }).click()
  await expect(page.getByLabel('Họ và tên', { exact: true })).toHaveValue(original.ho_ten)
  await expect(page.getByLabel('Trường công tác')).toHaveValue(original.truong)
  expect(errors).toEqual([])
})

test('a response for another teacher is rejected without overwriting the current account', async ({ page }) => {
  await setup(page, { response: { ...original, id_gv: 'different-teacher', ho_ten: 'Someone else' } })
  await edit(page)
  await page.getByRole('button', { name: 'Lưu thay đổi' }).click()
  await expect(page.getByRole('alert')).toContainText('Phản hồi cập nhật hồ sơ không hợp lệ')
  await expect(page.locator('header')).toContainText(original.ho_ten)
  await expect(page.locator('header')).not.toContainText('Someone else')
})
