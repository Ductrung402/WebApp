import { test, expect } from './fixtures/artifacts.js'
import { setup, student, makeRun, BASE } from './fixtures/examFlow.js'

const statePacket = (trangThai, stateVersion, code = 'AB12CD') => ({
  idHs: student.idHs, hoVaTen: student.hoVaTen, maCodePhongThi: code,
  idPhongThi: 'room-one', trangThai, stateVersion, stateChangedAt: BASE,
  disconnectedAt: BASE, frozenRemainingSeconds: 50,
})
const emit = (state, name, packet) => state.sockets.at(-1).send('42' + JSON.stringify([name, packet]))

test('teacher sees an orange offline card, stale polling cannot clear it, and recovery clears the badge', async ({ page }) => {
  const state = await setup(page, { teacher: true, run: makeRun() })
  await page.goto('/rooms/room-one/waiting')
  const card = page.getByTestId('student-student-one')
  await expect(card).toBeVisible()
  emit(state, 'student_disconnected', statePacket('MAT_KET_NOI', 1))
  await expect(card).toContainText('MẤT KẾT NỐI')
  await expect(card).toHaveClass(/bg-amber-50/)
  await page.clock.fastForward(3100)
  await expect(card).toContainText('MẤT KẾT NỐI')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: '../docs/screenshots/task_4_3/teacher-disconnected.png', fullPage: true })
  emit(state, 'student_reconnected', statePacket('DANG_THI', 2))
  await expect(card).not.toContainText('MẤT KẾT NỐI')
  emit(state, 'student_states_updated', { maCodePhongThi: 'AB12CD', hocSinhList: [statePacket('MAT_KET_NOI', 1)] })
  await expect(card).not.toContainText('MẤT KẾT NỐI')
})

test('teacher restores offline state after reload from a snapshot and ignores another room or invalid state', async ({ page }) => {
  const state = await setup(page, { teacher: true, run: makeRun() })
  await page.goto('/rooms/room-one/waiting')
  const card = page.getByTestId('student-student-one')
  await expect(card).toBeVisible()
  emit(state, 'student_disconnected', statePacket('MAT_KET_NOI', 1, 'QR34ST'))
  emit(state, 'student_disconnected', statePacket('INVALID', 1))
  await expect(card).not.toContainText('MẤT KẾT NỐI')
  await page.reload()
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối')
  emit(state, 'student_states_updated', { maCodePhongThi: 'AB12CD', hocSinhList: [statePacket('MAT_KET_NOI', 3)] })
  await expect(card).toContainText('MẤT KẾT NỐI')
})

test('student reconnects automatically, freezes choices and display offline, then recovers the real deadline and draft', async ({ page }) => {
  const state = await setup(page, { run: makeRun() })
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByTestId('exam-countdown')).toHaveText('01:00')
  await page.getByRole('radio', { name: 'Câu 1: B. CO2', exact: true }).check()
  await page.clock.fastForward(10000)
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:50')
  state.sockets.at(-1).close({ code: 1001, reason: 'network interruption' })
  await expect(page.getByRole('radio').first()).toBeDisabled()
  const frozen = await page.getByTestId('exam-countdown').textContent()
  let release
  state.recoveryGate = new Promise((resolve) => { release = resolve })
  state.serverNow = BASE + 20000
  const calls = state.calls.filter((call) => call.event === 'reconnect_exam').length
  await page.clock.fastForward(6000)
  await expect.poll(() => state.calls.filter((call) => call.event === 'reconnect_exam').length).toBeGreaterThan(calls)
  await expect(page.getByRole('radio').first()).toBeDisabled()
  await expect(page.getByTestId('exam-countdown')).toHaveText(frozen)
  await page.screenshot({ path: '../docs/screenshots/task_4_3/student-recovering.png', fullPage: true })
  release()
  await expect(page.getByRole('radio').first()).toBeEnabled()
  await expect(page.getByRole('radio', { name: 'Câu 1: B. CO2', exact: true })).toBeChecked()
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:40')
  expect(state.calls.filter((call) => call.event === 'reconnect_exam').at(-1).payload).toEqual({ idHs: student.idHs, maCodePhongThi: 'AB12CD' })
  await page.screenshot({ path: '../docs/screenshots/task_4_3/student-recovered.png', fullPage: true })
})

test('F5 calls recovery using the existing anonymous identity and restores selected answers', async ({ page }) => {
  const state = await setup(page, { run: makeRun() })
  await page.goto('/exam-room/AB12CD/taking')
  await page.getByRole('radio', { name: 'Câu 1: D. NaCl', exact: true }).check()
  const count = state.calls.filter((call) => call.event === 'reconnect_exam').length
  state.serverNow = BASE + 15000
  await page.reload()
  await expect.poll(() => state.calls.filter((call) => call.event === 'reconnect_exam').length).toBeGreaterThan(count)
  await expect(page.getByRole('radio', { name: 'Câu 1: D. NaCl', exact: true })).toBeChecked()
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:45')
  expect(state.calls.filter((call) => call.event === 'reconnect_exam').every((call) => call.payload.idHs === student.idHs)).toBe(true)
})

test('expired recovery keeps answers locked and sends the preserved snapshot once', async ({ page }) => {
  const state = await setup(page, { run: makeRun() })
  await page.goto('/exam-room/AB12CD/taking')
  await page.getByRole('radio', { name: 'Câu 1: C. O2', exact: true }).check()
  state.serverNow = BASE + 61000
  state.studentStatus = 'MAT_KET_NOI'
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn' })).toBeVisible()
  const calls = state.calls.filter((call) => call.event === 'submit_exam')
  expect(calls).toHaveLength(1)
  expect(calls[0].payload.reason).toBe('timeout')
  expect(calls[0].payload.answers).toEqual({ 'q-1': 'C' })
})

test('failed recovery cannot unlock work or submit, explicit retry succeeds without a new admission', async ({ page }) => {
  const state = await setup(page, { run: makeRun() })
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByRole('radio').first()).toBeEnabled()
  state.recoveryStatus = 503
  state.sockets.at(-1).close({ code: 1001, reason: 'network interruption' })
  await page.clock.fastForward(6000)
  await expect(page.getByTestId('socket-status')).toContainText('Máy chủ chưa xác nhận')
  await expect(page.getByRole('radio').first()).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Nộp bài', exact: true })).toBeDisabled()
  state.recoveryStatus = 200
  await page.getByRole('button', { name: 'Thử kết nối lại' }).click()
  await expect(page.getByRole('radio').first()).toBeEnabled()
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(0)
})

test('completed work is never unlocked or auto-submitted by recovery', async ({ page }) => {
  const state = await setup(page, { run: makeRun(), studentStatus: 'HOAN_THANH' })
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn' })).toBeVisible()
  await expect(page.getByRole('radio')).toHaveCount(0)
  await page.clock.fastForward(61000)
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(0)
})
