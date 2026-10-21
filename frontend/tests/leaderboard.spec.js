import { test, expect } from './fixtures/artifacts.js'
import { readFile } from 'node:fs/promises'
import { setup, student, makeRun } from './fixtures/examFlow.js'

const grade = (idHs = student.idHs, diemSo = 6.67) => ({ idHs, diemSo, soCauDung: 2, tongSoCau: 3, trangThai: 'HOAN_THANH' })
const classmate = { ...student, idHs: 'student-two', hoVaTen: 'Trần Bình' }
const emit = (state, event, packet) => state.sockets.at(-1).send('42' + JSON.stringify([event, packet]))
const submitted = (state, person, diemSo) => emit(state, 'student_submitted', { ...grade(person.idHs, diemSo), idPhongThi: 'room-one', maCodePhongThi: 'AB12CD', hoVaTen: person.hoVaTen })

test('closing a waiting room ends the waiting animation and explains how to join another session', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/exam-room/AB12CD/waiting')
  await expect(page.getByRole('heading', { name: 'Phòng chờ', exact: true })).toBeVisible()
  await expect.poll(() => state.calls.some(({ event }) => event === 'reconnect_exam')).toBe(true)
  state.emitRoomClosed()
  await expect(page.getByRole('heading', { name: 'Phòng thi đã kết thúc' })).toBeVisible()
  await expect(page.getByText('Trở về trang vào phòng để tham gia buổi thi khác.')).toBeVisible()
  await expect(page.locator('.animate-pulse')).toHaveCount(0)
})

test('student sees server score/correct count, encouragement and home action without a teacher Bearer header', async ({ page }) => {
  const state = await setup(page, { result: grade() })
  await page.addInitScript(() => localStorage.setItem('quizAi.teacherAccessToken', 'unrelated-teacher-token'))
  await page.goto('/exam-room/AB12CD/result')
  await expect(page.getByTestId('result-score')).toHaveText('6.67')
  await expect(page.getByTestId('result-correct')).toContainText('2/3 câu đúng')
  await expect(page.getByText('Bạn đã làm tốt!', { exact: true })).toBeVisible()
  const request = state.resultReads.at(-1)
  expect(new URL(request.url).searchParams.get('idHs')).toBe(student.idHs)
  expect(new URL(request.url).searchParams.get('idPhongThi')).toBe(student.idPhongThi)
  expect(request.headers.authorization).toBeUndefined()
  expect(state.sockets).toHaveLength(0)
  await page.screenshot({ path: '../docs/screenshots/task_5_2/student-result.png', fullPage: true })
  await page.getByRole('link', { name: 'Quay về trang chủ' }).click()
  await expect(page).toHaveURL('/')
  expect(await page.evaluate(() => sessionStorage.getItem('quizAi.studentSession'))).toBeNull()
})

test('submission ACK opens result immediately while server read is pending; reload reads it again', async ({ page }) => {
  let release
  const state = await setup(page, { run: makeRun(), result: grade(), resultGate: new Promise((resolve) => { release = resolve }) })
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByRole('radio').first()).toBeEnabled()
  await page.getByRole('button', { name: 'Nộp bài', exact: true }).click()
  await expect(page).toHaveURL(/\/result$/)
  await expect(page.getByTestId('result-score')).toHaveText('6.67')
  release()
  await expect(page.getByText('Đang xác nhận lại kết quả…')).toHaveCount(0)
  state.result = grade(student.idHs, 8.5)
  await page.reload()
  await expect(page.getByTestId('result-score')).toHaveText('8.5')
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(1)
})

test('result page rejects another room or missing session without requesting a score', async ({ page }) => {
  const state = await setup(page, { anonymous: true, result: grade() })
  await page.goto('/exam-room/AB12CD/result')
  await expect(page).toHaveURL(/\/join$/)
  expect(state.resultReads).toHaveLength(0)
  await page.evaluate((session) => sessionStorage.setItem('quizAi.studentSession', JSON.stringify(session)), student)
  await page.goto('/exam-room/QR34ST/result')
  await expect(page).toHaveURL(/\/join$/)
  expect(state.resultReads).toHaveLength(0)
})

test('invalid/wrong-student results never display a score; retry recovers a real zero score', async ({ page }) => {
  const state = await setup(page, { result: grade('someone-else') })
  await page.goto('/exam-room/AB12CD/result')
  await expect(page.getByRole('alert')).toContainText('chưa hợp lệ')
  await expect(page.getByTestId('result-score')).toHaveCount(0)
  state.result = { ...grade(), diemSo: 100 }
  await page.getByRole('button', { name: 'Tải lại kết quả' }).click()
  await expect(page.getByTestId('result-score')).toHaveCount(0)
  state.result = { ...grade(), diemSo: 0, soCauDung: 0 }
  await page.getByRole('button', { name: 'Tải lại kết quả' }).click()
  await expect(page.getByTestId('result-score')).toHaveText('0')
  await expect(page.getByTestId('result-correct')).toContainText('0/3')
  await expect(page.getByText('Mỗi lần làm bài là một cơ hội học hỏi.')).toBeVisible()
})

test('ungraded and unavailable results show recovery messages instead of invented points', async ({ page }) => {
  const state = await setup(page, { resultStatus: 409 })
  await page.goto('/exam-room/AB12CD/result')
  await expect(page.getByRole('alert')).toContainText('chưa có kết quả')
  await expect(page.getByTestId('result-score')).toHaveCount(0)
  state.resultStatus = 503
  await page.getByRole('button', { name: 'Tải lại kết quả' }).click()
  await expect(page.getByRole('alert')).toContainText('Chưa tải được')
  state.resultStatus = 200
  state.result = grade()
  await page.getByRole('button', { name: 'Tải lại kết quả' }).click()
  await expect(page.getByTestId('result-score')).toHaveText('6.67')
})

test('teacher grades update rows, sort descending, deduplicate retries and retain scores after stale polling', async ({ page }) => {
  const state = await setup(page, { teacher: true, run: makeRun(), students: [student, classmate] })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('leaderboard-total')).toHaveText('2')
  await expect(page.getByTestId('leaderboard-average')).toHaveText('—')
  submitted(state, student, 6)
  submitted(state, classmate, 8)
  submitted(state, classmate, 8)
  await expect(page.getByTestId('leaderboard-submitted')).toHaveText('2')
  await expect(page.getByTestId('leaderboard-average')).toHaveText('7')
  await expect(page.locator('[data-testid^="student-"]').first()).toHaveAttribute('data-testid', 'student-student-two')
  await expect(page.getByTestId('student-student-two')).toContainText('ĐÃ HOÀN THÀNH')
  await expect(page.getByTestId('student-student-two')).toHaveClass(/bg-emerald/)
  await page.clock.fastForward(3100)
  await expect(page.getByTestId('score-student-two')).toHaveText('8')
  emit(state, 'student_disconnected', { ...classmate, trangThai: 'MAT_KET_NOI', stateVersion: 20, stateChangedAt: Date.now() })
  await expect(page.getByTestId('score-student-two')).toHaveText('8')
  await expect(page.getByTestId('student-student-two')).not.toContainText('MẤT KẾT NỐI')
  await page.setViewportSize({ width: 1440, height: 1150 })
  await page.screenshot({ path: '../docs/screenshots/task_5_2/teacher-leaderboard.png', fullPage: true })
})

test('teacher ignores foreign-room and invalid grades and restores closed-room scores from REST on reload', async ({ page }) => {
  const state = await setup(page, { teacher: true, run: makeRun() })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('leaderboard-total')).toHaveText('1')
  emit(state, 'student_submitted', { ...grade(), hoVaTen: student.hoVaTen, maCodePhongThi: 'QR34ST' })
  submitted(state, student, 100)
  await expect(page.getByTestId('leaderboard-submitted')).toHaveText('0')
  state.closed = true
  state.scores = [{ ...student, trangThai: 'HOAN_THANH', diemSo: 0 }]
  await page.reload()
  await expect(page.getByTestId('score-student-one')).toHaveText('0')
  await expect(page.getByTestId('leaderboard-average')).toHaveText('0')
  await expect(page.getByText('Phòng đã đóng', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Đóng phòng thi', exact: true })).toHaveCount(0)
})

test('CSV uses descending ranking, preserves Vietnamese names and escapes quotes/formulas', async ({ page }) => {
  const oddName = { ...classmate, hoVaTen: '=SUM(1,2) "Bình"' }
  const state = await setup(page, { teacher: true, run: makeRun(), students: [student, oddName] })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('leaderboard-total')).toHaveText('2')
  submitted(state, student, 6)
  submitted(state, oddName, 8)
  await expect(page.getByTestId('leaderboard-submitted')).toHaveText('2')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Xuất CSV' }).click()
  const file = await download
  expect(file.suggestedFilename()).toBe('ket-qua-AB12CD.csv')
  const csv = await readFile(await file.path(), 'utf8')
  expect(csv.startsWith('\ufeff')).toBe(true)
  expect(csv).toContain("'=" + 'SUM(1,2) ""Bình""')
  expect(csv.indexOf('Bình')).toBeLessThan(csv.indexOf('Nguyễn Văn An'))
  expect(csv).toContain('ĐÃ HOÀN THÀNH')
})

test('close confirmation can cancel, disables duplicate requests and shows DA_DONG only after server confirmation', async ({ page }) => {
  let release
  const state = await setup(page, { teacher: true, run: makeRun(), closeGate: new Promise((resolve) => { release = resolve }) })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByRole('button', { name: 'Đóng phòng thi', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Đóng phòng thi', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('Còn 1 thí sinh chưa hoàn thành')
  await page.getByRole('button', { name: 'Tiếp tục theo dõi' }).click()
  expect(state.calls.filter((call) => call.event === 'close_exam_room')).toHaveLength(0)
  await page.getByRole('button', { name: 'Đóng phòng thi', exact: true }).click()
  await page.getByRole('button', { name: 'Xác nhận đóng phòng' }).click()
  await expect(page.getByRole('button', { name: 'Đang đóng…' })).toBeDisabled()
  await expect(page.getByText('ĐANG THI', { exact: true })).toBeVisible()
  release()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('Phòng đã đóng', { exact: true })).toBeVisible()
  await expect(page.getByTestId('student-student-one')).toContainText('CHƯA HOÀN THÀNH')
  await expect(page.getByTestId('score-student-one')).toHaveText('—')
  expect(state.calls.filter((call) => call.event === 'close_exam_room')).toHaveLength(1)
  expect(state.calls.find((call) => call.event === 'close_exam_room').payload).toEqual({ maCodePhongThi: 'AB12CD' })
})

test('close failure keeps room active and supports an explicit retry', async ({ page }) => {
  const state = await setup(page, { teacher: true, run: makeRun(), closeStatus: 503 })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByRole('button', { name: 'Đóng phòng thi', exact: true })).toBeEnabled()
  await page.getByRole('button', { name: 'Đóng phòng thi', exact: true }).click()
  await page.getByRole('button', { name: 'Xác nhận đóng phòng' }).click()
  await expect(page.getByRole('dialog')).toContainText('Chưa thể đóng')
  await expect(page.getByText('ĐANG THI', { exact: true })).toBeVisible()
  state.closeStatus = 200
  await page.getByRole('button', { name: 'Xác nhận đóng phòng' }).click()
  await expect(page.getByText('Phòng đã đóng', { exact: true })).toBeVisible()
})

test('room_closed stops student editing and auto-submit; another room closure is ignored', async ({ page }) => {
  const state = await setup(page, { run: makeRun() })
  await page.goto('/exam-room/AB12CD/taking')
  await expect(page.getByRole('radio').first()).toBeEnabled()
  state.emitRoomClosed('QR34ST')
  await expect(page.getByRole('radio').first()).toBeEnabled()
  state.emitRoomClosed()
  await expect(page.getByText(/Giáo viên đã đóng phòng thi/)).toBeVisible()
  await expect(page.getByRole('radio').first()).toBeDisabled()
  await page.clock.fastForward(61000)
  expect(state.calls.filter((call) => call.event === 'submit_exam')).toHaveLength(0)
})

test('mobile result and scoreboard remain readable with long names without page overflow', async ({ page }) => {
  const state = await setup(page, { teacher: true, run: makeRun(), result: grade(), students: [{ ...student, hoVaTen: 'Nguyễn Văn An tên rất dài để kiểm tra màn hình điện thoại' }] })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/exam-room/AB12CD/result')
  await expect(page.getByTestId('result-score')).toHaveText('6.67')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_5_2/result-mobile.png', fullPage: true })
  await page.goto('/rooms/room-one/waiting')
  await expect(page.getByTestId('leaderboard-total')).toHaveText('1')
  submitted(state, state.students[0], 8.5)
  await expect(page.getByTestId('score-student-one')).toHaveText('8.5')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_5_2/leaderboard-mobile.png', fullPage: true })
})
