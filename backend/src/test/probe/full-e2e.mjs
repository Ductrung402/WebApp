// Java QA: actual WAR/Tomcat/PostgreSQL/Socket/Gemini. Loopback fixtures only.
import assert from 'node:assert/strict'
import { mkdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../../../..')
process.chdir(resolve(root,'frontend'))
const require=createRequire(resolve(root,'frontend/package.json'))
const {chromium,expect}=require('@playwright/test')

let input = ''
for await (const chunk of process.stdin) input += chunk
const {apiUrl,socketUrl}=JSON.parse(input)
for(const url of [apiUrl,socketUrl])if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(url))throw Error('Loopback only')
process.env.VITE_API_BASE_URL=apiUrl;process.env.VITE_SOCKET_URL=socketUrl
const {createServer}=await import(pathToFileURL(resolve(root,'frontend/node_modules/vite/dist/node/index.js')).href)
const vite=await createServer({root:resolve(root,'frontend'),logLevel:'silent',server:{host:'localhost',port:5173,strictPort:true}});await vite.listen()
const base = 'http://localhost:5173'
const proofDir = pathToFileURL(resolve(root,'backend/target/verification/qa')+'/')
await mkdir(proofDir, { recursive: true })
const report = { recordedAt: new Date().toISOString(), browser: 'Google Chrome', ai: 'real Gemini API', steps: [], mobile: [], browserErrors: [], browserWarnings: [] }
const events = { student: [], teacher: [] }
const source = 'Nước có công thức hóa học H2O. Ở áp suất tiêu chuẩn, nước sôi ở 100 độ C và đóng băng ở 0 độ C. Trái Đất quay quanh Mặt Trời. Mặt Trăng quay quanh Trái Đất. Một năm thông thường có 365 ngày.'
const browser = await chromium.launch({ channel: 'chrome' })
const teacherContext = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const studentContext = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true })

function attach(page, type) {
  page.on('pageerror', (error) => report.browserErrors.push({ type, message: error.message }))
  page.on('console', (message) => {
    if (message.type() === 'warning') report.browserWarnings.push({ type, message: message.text() })
    if (message.type() === 'error') report.browserErrors.push({ type, message: message.text() })
  })
  page.on('websocket', (ws) => ws.on('framereceived', ({ payload }) => {
    if (typeof payload !== 'string') return
    const start = payload.indexOf('[')
    if (!payload.startsWith('42') && !payload.startsWith('43')) return
    try { events[type].push(JSON.parse(payload.slice(start))) } catch { /* Engine.IO heartbeat. */ }
  }))
}
function safe(value) {
  if (!value || typeof value !== 'object') return
  assert(!('dapAnDung' in value) && !('dap_an_dung' in value), 'Student received an answer key!')
  for (const child of Object.values(value)) safe(child)
}
function pass(step, detail) {
  report.steps.push({ step, status: 'PASS', detail })
  console.log(`PASS ${step}: ${detail}`)
}
async function screenshot(page, name) {
  await page.screenshot({ path: new URL(name + '.png', proofDir).pathname.replace(/^\/(\w:)/, '$1'), fullPage: true })
}
async function mobile(page, name) {
  for (const width of [320, 390, 768]) {
    await page.setViewportSize({ width, height: 844 })
    const dimensions = await page.evaluate(() => ({ width: innerWidth, content: document.documentElement.scrollWidth }))
    assert(dimensions.content <= dimensions.width + 1, `${name}: horizontal overflow at ${width}px`)
    report.mobile.push({ page: name, width, horizontalOverflow: false })
  }
  await page.setViewportSize({ width: 390, height: 844 })
  await screenshot(page, name)
}
async function api(path, token) {
  const response = await fetch(apiUrl + path, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  assert.equal(response.status, 200)
  return response.json()
}
function seconds(text) { const [m, s] = text.split(':').map(Number); return m * 60 + s }
let teacher, student
try {
  teacher = await teacherContext.newPage()
  attach(teacher, 'teacher')
  await teacher.goto(base + '/register')
  await teacher.getByLabel('Họ và tên', { exact: true }).fill('Giáo viên QA')
  await teacher.getByLabel('Email', { exact: true }).fill(`qa-${Date.now()}@example.com`)
  await teacher.getByLabel('Mật khẩu', { exact: true }).fill('QaTeacherPass123!')
  await teacher.getByLabel('Trường (không bắt buộc)').fill('Trường kiểm thử')
  await teacher.getByLabel('Tỉnh / Thành phố (không bắt buộc)').fill('TP Hồ Chí Minh')
  await teacher.getByRole('button', { name: 'Tạo tài khoản', exact: true }).click()
  await expect(teacher).toHaveURL(base + '/login')
  await teacher.getByLabel('Mật khẩu', { exact: true }).fill('QaTeacherPass123!')
  await teacher.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
  await expect(teacher).toHaveURL(base + '/dashboard')
  const token = await teacher.evaluate(() => localStorage.getItem('quizAi.teacherAccessToken'))
  assert.equal(token.split('.').length, 3)
  const identity = await api('/api/auth/me', token)
  assert(!('mat_khau' in identity))
  pass(1, 'Đăng ký/đăng nhập qua UI; JWT hợp lệ; /me ẩn mật khẩu.')

  await teacher.goto(base + '/exams/create-ai')
  await teacher.getByLabel('Tên đề thi', { exact: true }).fill('Đề E2E · Nước và thiên văn')
  await teacher.getByLabel('Khối lớp', { exact: true }).selectOption('6')
  await teacher.getByLabel('Số lượng câu hỏi', { exact: true }).fill('3')
  await teacher.getByLabel('Tệp văn bản (.txt)', { exact: true }).setInputFiles({ name: 'qa.txt', mimeType: 'text/plain', buffer: Buffer.from(source) })
  await expect(teacher.getByLabel('Văn bản tài liệu', { exact: true })).toHaveValue(source)
  const aiResponse = teacher.waitForResponse((r) => r.url().endsWith('/api/exams/preview-ai') && r.request().method() === 'POST')
  await teacher.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(teacher.getByRole('status')).toContainText('AI đang đọc tài liệu')
  await screenshot(teacher, 'ai-loading')
  const generated = await aiResponse
  assert.equal(generated.status(), 200, 'Real Gemini did not return a valid preview within 15 seconds.')
  const preview = await generated.json()
  assert.equal(preview.cauHoiList.length, 3)
  await writeFile(new URL('gemini_preview.json', proofDir), JSON.stringify(preview, null, 2))
  assert.equal((await api('/api/exams', token)).length, 0, 'Preview must not write an exam.')
  await expect(teacher.getByRole('heading', { name: 'Xem trước & chỉnh sửa câu hỏi' })).toBeVisible()
  await screenshot(teacher, 'ai-review')
  const savedResponse = teacher.waitForResponse((r) => r.url().endsWith('/api/exams') && r.request().method() === 'POST')
  await teacher.getByRole('button', { name: 'Lưu vào kho đề', exact: true }).click()
  const saved = await savedResponse
  assert.equal(saved.status(), 201)
  const examId = (await saved.json()).idDeThi
  await expect(teacher).toHaveURL(base + '/exams/' + examId)
  const exam = await api('/api/exams/' + examId, token)
  assert.equal(exam.cauHoiList.length, 3)
  pass(2, 'Upload UTF-8 .txt → Gemini thật → preview 3 câu không ghi DB → lưu kho đề PostgreSQL.')

  await teacher.goto(base + '/rooms/create?examId=' + examId)
  await teacher.getByLabel('Tên lớp', { exact: true }).fill('Lớp 6A · QA')
  await teacher.getByLabel('Thời gian làm bài (phút)', { exact: true }).fill('10')
  await teacher.getByRole('button', { name: 'Tạo phòng', exact: true }).click()
  await expect(teacher).toHaveURL(/\/rooms\/[^/]+\/waiting$/)
  const roomId = new URL(teacher.url()).pathname.split('/')[2]
  const room = await api('/api/rooms/' + roomId, token)
  assert.match(room.maCodePhongThi, /^[A-Z0-9]{6}$/)
  assert.equal(room.trangThaiPhong, 'DANG_CHO')
  report.roomCreated = room
  const code = room.maCodePhongThi
  pass(3, 'Phòng mới DANG_CHO; mã 6 ký tự chữ in hoa và số.')

  student = await studentContext.newPage()
  attach(student, 'student')
  student.on('request', (request) => {
    if (request.url().startsWith(apiUrl + '/api/')) assert(!request.headers().authorization, 'Student sent a teacher Bearer token.')
  })
  await student.goto(base + '/join')
  await mobile(student, 'student-join')
  await student.getByLabel('Họ và tên', { exact: true }).fill('Nguyễn Văn An QA')
  await student.getByLabel('Mã phòng thi', { exact: true }).fill(code)
  await student.getByRole('button', { name: 'Vào phòng thi', exact: true }).click()
  await expect(student).toHaveURL(base + `/exam-room/${code}/waiting`)
  await expect(student.getByRole('heading', { name: 'Phòng chờ', exact: true })).toBeVisible()
  await mobile(student, 'student-waiting')
  const session = await student.evaluate(() => JSON.parse(sessionStorage.getItem('quizAi.studentSession')))
  assert(session.idHs && session.maCodePhongThi === code)
  assert.equal(await student.evaluate(() => localStorage.getItem('quizAi.studentSession')), null)
  pass(4, 'Chrome context ẩn danh/mobile 390px; vào phòng không tài khoản; sessionStorage lưu định danh.')

  await expect(teacher.getByRole('button', { name: 'Bắt đầu làm bài', exact: true })).toBeEnabled({ timeout: 15000 })
  await teacher.getByRole('button', { name: 'Bắt đầu làm bài', exact: true }).click()
  await expect(student).toHaveURL(base + `/exam-room/${code}/taking`)
  await expect(student.getByRole('radio')).toHaveCount(12)
  await expect(student.getByTestId('exam-countdown')).toHaveText(/\d+:\d{2}/)
  const run = events.student.find(([name]) => name === 'exam_started')?.[1]
  assert(run, 'Student did not receive exam_started.')
  safe(run)
  assert.equal(run.trangThaiPhong, 'DANG_THI')
  const teacherTimer = seconds(await teacher.getByTestId('exam-countdown').innerText())
  const studentTimer = seconds(await student.getByTestId('exam-countdown').innerText())
  assert(Math.abs(teacherTimer - studentTimer) <= 1)
  await expect.poll(async () => seconds(await student.getByTestId('exam-countdown').innerText()), { timeout: 5000 }).toBeLessThan(studentTimer)
  report.countdown = { teacherSeconds: teacherTimer, studentSeconds: studentTimer, maximumSkewSeconds: 1, ticksDown: true }
  const oracle = new Map(exam.cauHoiList.map((q) => [q.idCauHoi, q.dapAnDung]))
  for (const q of run.cauHoiList.slice(0, 2)) {
    await student.locator(`input[name="answer-${q.idCauHoi}"][value="${oracle.get(q.idCauHoi)}"]`).check()
  }
  await mobile(student, 'student-taking')
  await writeFile(new URL('exam_started.json', proofDir), JSON.stringify(run, null, 2))
  pass(5, 'DANG_CHO → DANG_THI; 12 radio/3 câu; payload không có đáp án đúng; hai đồng hồ lệch ≤1 giây.')

  await student.close()
  const row = teacher.getByTestId(`student-${session.idHs}`)
  await expect(row).toContainText('MẤT KẾT NỐI', { timeout: 15000 })
  const disconnected = (await api('/api/rooms/' + roomId, token)).ketQuaList.find((r) => r.idHs === session.idHs)
  assert.equal(disconnected.trangThai, 'MAT_KET_NOI')
  report.disconnected = events.teacher.find(([name]) => name === 'student_disconnected')?.[1]
  await screenshot(teacher, 'teacher-disconnected')
  pass(6, 'Đóng tab thật → PostgreSQL MAT_KET_NOI → thẻ giáo viên MẤT KẾT NỐI.')

  student = await studentContext.newPage()
  attach(student, 'student')
  // Model browser Restore Closed Tab: restore that tab's original sessionStorage.
  // A completely new tab has no identity, by the sessionStorage contract.
  await student.addInitScript((value) => sessionStorage.setItem('quizAi.studentSession', JSON.stringify(value)), session)
  await student.goto(base + `/exam-room/${code}/taking`)
  await expect(student.getByRole('radio', { checked: true })).toHaveCount(2)
  await expect(student.getByRole('button', { name: 'Nộp bài', exact: true })).toBeEnabled()
  await expect(row).toContainText('ĐANG LÀM BÀI')
  await student.reload()
  await expect(student.getByRole('radio', { checked: true })).toHaveCount(2)
  await expect(student.getByRole('button', { name: 'Nộp bài', exact: true })).toBeEnabled()
  const recovery = events.student.flat().find((p) => p && typeof p === 'object' && p.studentState?.trangThai === 'DANG_THI' && p.exam)
  assert(recovery)
  assert.equal(recovery.exam.endsAt, run.endsAt)
  safe(recovery)
  report.recovery = { endsAt: recovery.exam.endsAt, trangThai: recovery.studentState.trangThai, remainingSeconds: Math.max(0, Math.ceil((recovery.exam.endsAt - recovery.serverTime) / 1000)), preservedAnswers: 2 }
  await screenshot(student, 'student-recovered')
  pass(7, 'Khôi phục cùng phiên tab + F5: DANG_THI, giữ 2 đáp án và deadline; không cấp thêm thời gian.')

  await student.getByRole('button', { name: 'Nộp bài', exact: true }).click()
  await expect(student).toHaveURL(base + `/exam-room/${code}/result`)
  await expect(student.getByTestId('result-score')).toHaveText('6.67')
  await expect(student.getByTestId('result-correct')).toContainText('2/3 câu đúng')
  await expect(row).toContainText('ĐÃ HOÀN THÀNH')
  await expect(teacher.getByTestId(`score-${session.idHs}`)).toHaveText('6.67')
  await expect(teacher.getByTestId('leaderboard-submitted')).toHaveText('1')
  await expect(teacher.getByTestId('leaderboard-average')).toHaveText('6.67')
  assert(events.teacher.some(([name]) => name === 'student_submitted'), 'Teacher missed realtime grade event.')
  await mobile(student, 'student-result')
  await student.reload()
  await expect(student.getByTestId('result-score')).toHaveText('6.67')
  report.result = await api(`/api/submissions/result?idHs=${session.idHs}&idPhongThi=${roomId}`)
  pass(8, '2/3 câu đúng → 6.67/10 HOAN_THANH; student_submitted cập nhật bảng điểm/điểm trung bình; F5 giữ kết quả.')

  const download = teacher.waitForEvent('download')
  await teacher.getByRole('button', { name: 'Xuất CSV', exact: true }).click()
  await (await download).saveAs(new URL('results.csv', proofDir).pathname.replace(/^\/(\w:)/, '$1'))
  await teacher.getByRole('button', { name: 'Đóng phòng thi', exact: true }).click()
  await teacher.getByRole('dialog').getByRole('button', { name: 'Xác nhận đóng phòng', exact: true }).click()
  await expect(teacher.getByRole('heading', { name: 'Phòng đã đóng · Lớp 6A · QA', exact: true })).toBeVisible()
  report.closed = await api('/api/rooms/' + roomId, token)
  assert.equal(report.closed.trangThaiPhong, 'DA_DONG')
  await screenshot(teacher, 'teacher-leaderboard')
  await student.getByRole('link', { name: 'Quay về trang chủ', exact: true }).click()
  await expect(student).toHaveURL(base + '/')
  assert.equal(await student.evaluate(() => sessionStorage.getItem('quizAi.studentSession')), null)
  for (const packet of events.student) safe(packet)
  assert.deepEqual(report.browserErrors, [])
  assert.deepEqual(report.browserWarnings, [])
  await writeFile(new URL('e2e_results.json', proofDir), JSON.stringify(report, null, 2) + '\n')
} catch (error) {
  report.failure = 'Full E2E failed; inspect the last completed step and local screenshots.'
  await writeFile(new URL('e2e_failure.json', proofDir), JSON.stringify(report, null, 2) + '\n')
  if (teacher && !teacher.isClosed()) await screenshot(teacher, 'failure-teacher')
  if (student && !student.isClosed()) await screenshot(student, 'failure-student')
  process.exitCode = 1
} finally {
  await browser.close()
  await vite.close()
}
