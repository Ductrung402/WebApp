import { test, expect } from '@playwright/test'

const tokenKey = 'quizAi.teacherAccessToken'
const teacher = { id_gv: 'password-teacher', email: 'teacher@example.com', ho_ten: 'Nguyễn Văn A', truong: 'THPT A', tinh: 'TP HCM' }
const initialPassword = 'CurrentPass123!'
const newPassword = '  NewSecret456!  '
const cors = { 'access-control-allow-origin': 'http://localhost:5173', 'access-control-allow-methods': 'GET, POST, PUT, OPTIONS', 'access-control-allow-headers': 'authorization,content-type' }

async function setup(page, options = {}) {
  const state = { password: initialPassword, status: 200, calls: [], ...options }
  await page.addInitScript((key) => localStorage.setItem(key, 'password-test-token'), tokenKey)
  await page.route('**/api/auth/**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const path = new URL(request.url()).pathname
    let status = 200, body = teacher
    if (path === '/api/auth/login') {
      expect(request.headers().authorization).toBeUndefined()
      const payload = request.postDataJSON()
      status = payload.email === teacher.email && payload.mat_khau === state.password ? 200 : 401
      body = status === 200 ? { access_token: 'password-test-token', token_type: 'bearer' } : { detail: 'Incorrect email or password.' }
    } else {
      expect(request.headers().authorization).toBe('Bearer password-test-token')
      if (path === '/api/auth/change-password') {
        expect(request.method()).toBe('PUT')
        const payload = request.postDataJSON()
        state.calls.push(payload)
        if (state.gate) await state.gate
        status = state.status === 200 && payload.mat_khau_cu !== state.password ? 400 : state.status
        if (status === 200) state.password = payload.mat_khau_moi
        body = status === 200 ? { status: 'ok', message: 'Đã cập nhật mật khẩu.' } : { detail: status === 400 ? 'Mật khẩu hiện tại không chính xác.' : 'Password update failed' }
      } else expect(path).toBe('/api/auth/me')
    }
    await route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) })
  })
  await page.goto('/dashboard')
  await expect(page.getByRole('button', { name: 'Đổi mật khẩu', exact: true })).toBeVisible()
  await expect(page.locator('input[type="password"]')).toHaveCount(0)
  return state
}

async function fillPasswords(page, current = initialPassword, next = newPassword, confirmation = next) {
  if (!await page.getByRole('dialog', { name: 'Đổi mật khẩu', exact: true }).isVisible()) await page.getByRole('button', { name: 'Đổi mật khẩu', exact: true }).click()
  await page.getByLabel('Mật khẩu hiện tại', { exact: true }).fill(current)
  await page.getByLabel('Mật khẩu mới', { exact: true }).fill(next)
  await page.getByLabel('Xác nhận mật khẩu mới', { exact: true }).fill(confirmation)
}

const submit = (page) => page.getByRole('button', { name: 'Cập nhật mật khẩu', exact: true }).click()

test('change preserves password whitespace, clears all fields and allows logout/login only with the new password', async ({ page }) => {
  const state = await setup(page)
  await fillPasswords(page)
  await submit(page)
  await expect(page.getByRole('status')).toHaveText('Đã đổi mật khẩu thành công.')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(state.calls).toEqual([{ mat_khau_cu: initialPassword, mat_khau_moi: newPassword }])
  await page.getByRole('button', { name: 'Đổi mật khẩu', exact: true }).click()
  for (const label of ['Mật khẩu hiện tại', 'Mật khẩu mới', 'Xác nhận mật khẩu mới']) await expect(page.getByLabel(label, { exact: true })).toHaveValue('')
  await page.getByRole('button', { name: 'Đóng hộp thoại đổi mật khẩu' }).click()
  const storage = await page.evaluate(() => JSON.stringify([localStorage, sessionStorage]))
  expect(storage).not.toContain(initialPassword)
  expect(storage).not.toContain(newPassword)
  await expect(page.locator('header')).toContainText(teacher.ho_ten)
  await page.getByRole('button', { name: 'Đăng xuất', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
  await page.getByLabel('Email', { exact: true }).fill(teacher.email)
  await page.getByLabel('Mật khẩu', { exact: true }).fill(initialPassword)
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Email hoặc mật khẩu không chính xác')
  await page.getByLabel('Mật khẩu', { exact: true }).fill(newPassword)
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  await expect(page.locator('header')).toContainText(teacher.ho_ten)
})

test('incorrect current password shows exact error and keeps values for correction', async ({ page }) => {
  const state = await setup(page)
  await fillPasswords(page, 'WrongCurrent123!')
  await submit(page)
  await expect(page.getByRole('alert')).toHaveText('Mật khẩu hiện tại không chính xác.')
  await expect(page.getByLabel('Mật khẩu mới', { exact: true })).toHaveValue(newPassword)
  expect(state.password).toBe(initialPassword)
  await page.getByLabel('Mật khẩu hiện tại', { exact: true }).fill(initialPassword)
  await submit(page)
  await expect(page.getByRole('status')).toHaveText('Đã đổi mật khẩu thành công.')
})

test('mismatch, short password and excess UTF-8 bytes never reach the API', async ({ page }) => {
  const state = await setup(page)
  await fillPasswords(page, initialPassword, 'ValidNew123!', 'Different123!')
  await submit(page)
  await expect(page.getByText('Mật khẩu xác nhận không khớp.', { exact: true })).toBeVisible()
  await fillPasswords(page, initialPassword, 'short')
  await submit(page)
  await expect(page.getByText('Mật khẩu cần ít nhất 8 ký tự.', { exact: true })).toBeVisible()
  await fillPasswords(page, initialPassword, 'é'.repeat(37))
  await submit(page)
  await expect(page.getByText('Mật khẩu tối đa 72 byte và không chứa ký tự null.', { exact: true })).toBeVisible()
  expect(state.calls).toHaveLength(0)
})

test('busy password form stays locked and does not clear inputs before confirmation', async ({ page }) => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await setup(page, { gate })
  try {
    await fillPasswords(page)
    await submit(page)
    await expect(page.getByRole('button', { name: 'Đang cập nhật…' })).toBeDisabled()
    for (const label of ['Mật khẩu hiện tại', 'Mật khẩu mới', 'Xác nhận mật khẩu mới']) await expect(page.getByLabel(label, { exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Hủy', exact: true })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Đóng hộp thoại đổi mật khẩu' })).toBeDisabled()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByLabel('Mật khẩu hiện tại', { exact: true })).toHaveValue(initialPassword)
    await expect(page.getByRole('status')).toHaveCount(0)
    await expect.poll(() => state.calls.length).toBe(1)
  } finally { release() }
  await expect(page.getByRole('status')).toHaveText('Đã đổi mật khẩu thành công.')
  expect(state.calls).toHaveLength(1)
})

test('failed password transaction retains values and supports explicit retry', async ({ page }) => {
  const state = await setup(page, { status: 503 })
  await fillPasswords(page)
  await submit(page)
  await expect(page.getByRole('alert')).toContainText('Máy chủ đang gặp sự cố')
  await expect(page.getByLabel('Mật khẩu mới', { exact: true })).toHaveValue(newPassword)
  expect(state.password).toBe(initialPassword)
  state.status = 200
  await submit(page)
  await expect(page.getByRole('status')).toHaveText('Đã đổi mật khẩu thành công.')
  expect(state.calls).toHaveLength(2)
})

test('expired JWT during password change logs out without a success message', async ({ page }) => {
  await setup(page, { status: 401 })
  await fillPasswords(page)
  await submit(page)
  await expect(page).toHaveURL(/\/login$/)
  expect(await page.evaluate((key) => localStorage.getItem(key), tokenKey)).toBeNull()
  await expect(page.getByText('Đã đổi mật khẩu thành công.', { exact: true })).toHaveCount(0)
})

test('mobile dialog masks passwords, fits the viewport and has no horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await setup(page)
  await page.screenshot({ path: '../docs/screenshots/task_6_5/profile-mobile.png', fullPage: true })
  await page.getByRole('button', { name: 'Đổi mật khẩu', exact: true }).click()
  for (const label of ['Mật khẩu hiện tại', 'Mật khẩu mới', 'Xác nhận mật khẩu mới']) await expect(page.getByLabel(label, { exact: true })).toHaveAttribute('type', 'password')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  const bounds = await page.getByRole('dialog').boundingBox()
  expect(bounds.x).toBeGreaterThanOrEqual(0)
  expect(bounds.y).toBeGreaterThanOrEqual(0)
  expect(bounds.x + bounds.width).toBeLessThanOrEqual(390)
  expect(bounds.y + bounds.height).toBeLessThanOrEqual(844)
  await page.screenshot({ path: '../docs/screenshots/task_6_5/password-mobile.png', fullPage: true })
})

test('cancel, close button and Escape discard secrets and restore focus to the trigger', async ({ page }) => {
  const state = await setup(page)
  const trigger = page.getByRole('button', { name: 'Đổi mật khẩu', exact: true })
  for (const action of ['cancel', 'close', 'escape']) {
    await trigger.click()
    await expect(page.getByLabel('Mật khẩu hiện tại', { exact: true })).toBeFocused()
    await page.getByLabel('Mật khẩu hiện tại', { exact: true }).fill(initialPassword)
    await page.getByLabel('Mật khẩu mới', { exact: true }).fill(newPassword)
    if (action === 'escape') await page.keyboard.press('Escape')
    else await page.getByRole('button', { name: action === 'cancel' ? 'Hủy' : 'Đóng hộp thoại đổi mật khẩu', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.locator('input[type="password"]')).toHaveCount(0)
    await expect(trigger).toBeFocused()
    await trigger.click()
    for (const label of ['Mật khẩu hiện tại', 'Mật khẩu mới', 'Xác nhận mật khẩu mới']) await expect(page.getByLabel(label, { exact: true })).toHaveValue('')
    await page.keyboard.press('Escape')
  }
  expect(state.calls).toHaveLength(0)
})

test('all three password fields are required and modal focus stays inside the dialog', async ({ page }) => {
  const state = await setup(page)
  await page.getByRole('button', { name: 'Đổi mật khẩu', exact: true }).click()
  await submit(page)
  await expect(page.getByText('Mật khẩu cần ít nhất 8 ký tự.', { exact: true })).toHaveCount(2)
  await expect(page.getByText('Vui lòng xác nhận mật khẩu mới.', { exact: true })).toBeVisible()
  expect(state.calls).toHaveLength(0)
  for (let index = 0; index < 12; index += 1) {
    await page.keyboard.press('Tab')
    expect(await page.evaluate(() => document.querySelector('dialog').contains(document.activeElement))).toBe(true)
  }
  await page.getByRole('button', { name: 'Đóng hộp thoại đổi mật khẩu' }).focus()
  await page.keyboard.press('Shift+Tab')
  await expect(page.getByRole('button', { name: 'Cập nhật mật khẩu', exact: true })).toBeFocused()
})
