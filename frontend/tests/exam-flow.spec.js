import { test, expect } from './fixtures/artifacts.js'

import { setup, student, question, makeRun, BASE } from './fixtures/examFlow.js'

async function taking(page, state) {
  state.run ||= makeRun()
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByRole('heading', { name: 'Kiến thức về nước', exact: true })).toBeVisible()
  await expect(page.getByTestId('exam-countdown')).toHaveText('01:00')
}

test('teacher starts room and shows DANG_THI with a synchronized countdown', async ({ page }) => {
  const state = await setup(page, { teacher: true })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByRole('button', { name: 'Bắt đầu làm bài', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Bắt đầu làm bài', exact: true }).click()
  await expect(page.getByText('ĐANG THI', { exact: true })).toBeVisible()
  await expect(page.getByTestId('exam-countdown')).toHaveText('01:00')
  await expect(page.getByRole('button', { name: 'Bắt đầu làm bài', exact: true })).toBeDisabled()
  expect(state.calls.filter((call) => call.event === 'start_exam')).toHaveLength(1)
  expect(state.calls.find((call) => call.event === 'start_exam').payload).toEqual({ maCodePhongThi: 'AB12CD' })
  await page.screenshot({ path: '../docs/screenshots/task_4_2/teacher-live.png', fullPage: true })
  await page.clock.fastForward(10000)
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:50')
})

test('exam_started automatically opens student taking screen with four radios and no answer key', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/exam-room/AB12CD/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối')
  state.run = makeRun()
  state.emitStarted()
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/taking$/)
  await expect(page.getByRole('radio')).toHaveCount(4)
  await expect(page.getByTestId('exam-countdown')).toHaveText('01:00')
  expect(await page.locator('body').textContent()).not.toContain('dapAnDung')
  expect(state.calls.filter((call) => call.event === 'start_exam')).toHaveLength(0)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: '../docs/screenshots/task_4_2/student-taking.png', fullPage: true })
})

test('choices survive reload and countdown restores the original server deadline', async ({ page }) => {
  const state = await setup(page)
  await taking(page, state)
  await page.getByRole('radio', { name: 'Câu 1: B. CO2', exact: true }).check()
  await page.clock.fastForward(10000)
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:50')
  state.serverNow = BASE + 10000
  await page.reload()
  await expect(page.getByRole('radio', { name: 'Câu 1: B. CO2', exact: true })).toBeChecked()
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:50')
  const cached = await page.evaluate(() => JSON.parse(localStorage.getItem('quizAi.studentAnswers.student-one.run-one')))
  expect(cached.answers).toEqual({ 'q-1': 'B' })
})

test('manual submission freezes choices and waits for a real server receipt', async ({ page }) => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await setup(page, { submitGate: gate })
  await taking(page, state)
  await page.getByRole('radio', { name: 'Câu 1: A. H2O', exact: true }).check()
  await page.getByRole('button', { name: 'Nộp bài', exact: true }).click()
  await expect(page.getByRole('radio').first()).toBeDisabled()
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn' })).toHaveCount(0)
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(1)
  release()
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn', exact: true })).toBeVisible()
  expect(state.calls.find((call) => call.event === 'submit_exam').payload).toEqual({ maCodePhongThi: 'AB12CD', runId: 'run-one', answers: { 'q-1': 'A' }, reason: 'manual' })
  await page.screenshot({ path: '../docs/screenshots/task_4_2/submission-received.png', fullPage: true })
})

test('at 00:00 automatic submission fires once and includes the latest selections', async ({ page }) => {
  const state = await setup(page, { run: makeRun(BASE - 57000) })
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:03')
  await page.getByRole('radio', { name: 'Câu 1: C. O2', exact: true }).check()
  state.serverNow = BASE + 4000
  await page.clock.fastForward(4000)
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn', exact: true })).toBeVisible()
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(1)
  expect(state.calls.find((call) => call.event === 'submit_exam').payload).toMatchObject({ reason: 'timeout', answers: { 'q-1': 'C' } })
  await page.clock.fastForward(10000)
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(1)
})

test('failed submission stays pending and explicit retry sends the same frozen answers', async ({ page }) => {
  const state = await setup(page, { submitStatus: 503 })
  await taking(page, state)
  await page.getByRole('radio', { name: 'Câu 1: B. CO2', exact: true }).check()
  await page.getByRole('button', { name: 'Nộp bài', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Máy chủ chưa xác nhận')
  await expect(page.getByRole('radio').first()).toBeDisabled()
  state.submitStatus = 200
  await page.getByRole('button', { name: 'Thử gửi lại bài', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn' })).toBeVisible()
  const calls = state.calls.filter((call) => call.event === 'submit_exam')
  expect(calls).toHaveLength(2)
  expect(calls[1].payload).toEqual(calls[0].payload)
})

test('reload after accepted submission restores receipt without sending again', async ({ page }) => {
  const state = await setup(page)
  await taking(page, state)
  await page.getByRole('button', { name: 'Nộp bài', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn' })).toBeVisible()
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(1)
})

test('system clock changes do not reset or skip the monotonic countdown', async ({ page }) => {
  const state = await setup(page)
  await taking(page, state)
  await page.clock.setSystemTime(new Date(BASE + 86400000))
  await page.clock.fastForward(5000)
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:55')
})

test('student with no started exam returns to waiting and missing session returns to join', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page).toHaveURL(/\/exam-room\/AB12CD\/waiting$/)
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(0)
  const anonymous = await page.context().newPage()
  await setup(anonymous, { anonymous: true })
  await anonymous.goto('/exam-room/AB12CD/taking')
  await expect(anonymous).toHaveURL(/\/join$/)
  await anonymous.close()
})

test('unsafe question payload and a start for another room never open taking', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/exam-room/AB12CD/waiting')
  await expect(page.getByTestId('socket-status')).toContainText('Đã kết nối')
  state.emitStarted({ ...makeRun(), maCodePhongThi: 'QR34ST' })
  await expect(page).toHaveURL(/\/waiting$/)
  state.emitStarted({ ...makeRun(), cauHoiList: [{ ...question, dapAnDung: 'A' }] })
  await expect(page.getByTestId('socket-status')).toContainText('Đề thi nhận được chưa hợp lệ')
  await expect(page).toHaveURL(/\/waiting$/)
})

test('start failure leaves teacher waiting and retry succeeds', async ({ page }) => {
  const state = await setup(page, { teacher: true, startStatus: 503 })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByRole('button', { name: 'Bắt đầu làm bài' })).toBeEnabled()
  await page.getByRole('button', { name: 'Bắt đầu làm bài' }).click()
  await expect(page.getByRole('alert')).toContainText('Chưa thể bắt đầu')
  await expect(page.getByText('ĐANG THI', { exact: true })).toHaveCount(0)
  state.startStatus = 200
  await page.getByRole('button', { name: 'Bắt đầu làm bài' }).click()
  await expect(page.getByText('ĐANG THI', { exact: true })).toBeVisible()
})

test('mobile taking screen has no horizontal overflow and all four choices are usable', async ({ page }) => {
  const state = await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await taking(page, state)
  await page.getByRole('radio', { name: 'Câu 1: D. NaCl', exact: true }).check()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_4_2/taking-mobile.png', fullPage: true })
})

test('temporary clock sync failure recovers and sends the pending timeout submission', async ({ page }) => {
  const state = await setup(page)
  await taking(page, state)
  state.syncStatus = 503
  state.serverNow = BASE + 31000
  await page.clock.fastForward(31000)
  await expect(page.getByTestId('socket-status')).toContainText('Máy chủ chưa xác nhận')
  state.syncStatus = 200
  state.serverNow = BASE + 62000
  await page.clock.fastForward(31000)
  await expect(page.getByRole('heading', { name: 'Kết quả của bạn', exact: true })).toBeVisible()
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(1)
})

test('clock synchronization subtracts server processing time from round-trip latency', async ({ page }) => {
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await setup(page, { run: makeRun(), syncGate: gate })
  await page.goto('/exam-room/AB12CD/taking')
  await expect.poll(() => state.calls.some((call) => ['sync_exam', 'reconnect_exam'].includes(call.event))).toBe(true)
  await page.clock.fastForward(4000)
  state.serverNow = BASE + 4000
  release()
  await expect(page.getByTestId('exam-countdown')).toHaveText('00:56')
})
