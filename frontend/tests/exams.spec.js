import { test, expect } from '@playwright/test'

const text = 'Nước có công thức hóa học H2O. Nước sôi ở 100 độ C và đóng băng ở 0 độ C ở áp suất tiêu chuẩn.'
const question = { noiDung: 'Công thức hóa học của nước là gì?', dapAnA: 'H2O', dapAnB: 'CO2', dapAnC: 'O2', dapAnD: 'NaCl', dapAnDung: 'A' }
const exam = {
  idDeThi: 'exam-one', idGv: 'teacher-test-id', tenDeThi: 'Kiến thức về nước', khoi: '6',
  soLuongCauHoi: 2, ngayTao: '2026-10-13', kieuTaiLieu: 'TEXT', noiDungTaiLieu: text,
  cauHoiList: [{ ...question, idCauHoi: 'q-1', idDeThi: 'exam-one' }, { ...question, noiDung: 'Chọn công thức của nước.', idCauHoi: 'q-2', idDeThi: 'exam-one' }],
}
const cors = {
  'access-control-allow-origin': 'http://localhost:5173',
  'access-control-allow-methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'access-control-allow-headers': 'authorization,content-type',
}
const respond = (route, status, body) => route.fulfill({ status, headers: cors, contentType: 'application/json', body: JSON.stringify(body) })

async function mockExams(page, options = {}) {
  const state = { records: structuredClone(options.records ?? [exam]), calls: [], previewStatus: 200, deleteStatus: 200, updateStatus: 200, listStatus: 200, ...options }
  await page.route('**/api/exams**', async (route) => {
    const request = route.request()
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors })
    const path = new URL(request.url()).pathname
    const payload = request.postData() ? request.postDataJSON() : null
    state.calls.push({ path, method: request.method(), payload })
    expect(request.headers().authorization).toBe('Bearer valid-test-token')
    if (path.endsWith('/preview-ai')) {
      if (state.previewGate) await state.previewGate
      if (state.previewStatus !== 200) return respond(route, state.previewStatus, { detail: 'Gemini generation exceeded the 15-second timeout.' })
      return respond(route, 200, { tenDeThi: payload.tenDeThi, khoi: payload.khoi, kieuTaiLieu: 'TEXT', noiDungTaiLieu: payload.rawText, cauHoiList: [question, { ...question, noiDung: 'Chọn công thức của nước.' }] })
    }
    if (path === '/api/exams' && request.method() === 'GET') return respond(route, state.listStatus, state.listStatus === 200 ? state.records : { detail: 'Unavailable' })
    if (path === '/api/exams' && request.method() === 'POST') {
      if (state.saveStatus && state.saveStatus !== 201) return respond(route, state.saveStatus, { detail: 'Unavailable' })
      const saved = { ...exam, ...payload, idDeThi: 'saved-exam', soLuongCauHoi: payload.cauHoiList.length, cauHoiList: payload.cauHoiList.map((item, index) => ({ ...item, idCauHoi: `saved-q-${index}`, idDeThi: 'saved-exam' })) }
      state.records.unshift(saved)
      return respond(route, 201, saved)
    }
    const id = decodeURIComponent(path.split('/').at(-1))
    const record = state.records.find((item) => item.idDeThi === id)
    if (!record) return respond(route, 404, { detail: 'Exam not found.' })
    if (request.method() === 'GET') return respond(route, 200, record)
    if (request.method() === 'PUT') {
      if (state.updateStatus !== 200) return respond(route, state.updateStatus, { detail: 'Exam in use.' })
      Object.assign(record, payload, { soLuongCauHoi: payload.cauHoiList.length, cauHoiList: payload.cauHoiList.map((item, index) => ({ ...item, idCauHoi: `updated-q-${index}`, idDeThi: id })) })
      return respond(route, 200, record)
    }
    if (request.method() === 'DELETE') {
      if (state.deleteStatus !== 200) return respond(route, state.deleteStatus, { detail: 'Exam in use.' })
      state.records = state.records.filter((item) => item.idDeThi !== id)
      return respond(route, 200, { idDeThi: id, status: 'deleted' })
    }
    return respond(route, 400, { detail: 'Unexpected request' })
  })
  return state
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('quizAi.teacherAccessToken', 'valid-test-token'))
  await page.route('**/api/auth/me', (route) => route.request().method() === 'OPTIONS'
    ? route.fulfill({ status: 204, headers: cors })
    : respond(route, 200, { id_gv: 'teacher-test-id', email: 'teacher@example.com', ho_ten: 'Nguyễn Văn A', truong: 'THPT A', tinh: 'TP HCM' }))
})

async function fillSource(page) {
  await page.getByLabel('Tên đề thi', { exact: true }).fill('Ôn tập về nước')
  await page.getByLabel('Khối lớp', { exact: true }).selectOption('6')
  await page.getByLabel('Số lượng câu hỏi', { exact: true }).fill('2')
  await page.getByLabel('Văn bản tài liệu', { exact: true }).fill(text)
}

test('AI loading, preview edits and explicit save create exactly one reviewed exam', async ({ page }) => {
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  let release
  const gate = new Promise((resolve) => { release = resolve })
  const state = await mockExams(page, { records: [], previewGate: gate })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/exams/create-ai')
  await fillSource(page)
  await page.screenshot({ path: '../docs/screenshots/task_2_3/create-ai.png', fullPage: true })
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(page.getByRole('status')).toContainText('AI đang đọc tài liệu')
  await expect(page.getByRole('button', { name: 'AI đang sinh câu hỏi…' })).toBeDisabled()
  await page.screenshot({ path: '../docs/screenshots/task_2_3/loading-ai.png', fullPage: true })
  release()
  await expect(page.getByRole('heading', { name: 'Xem trước & chỉnh sửa câu hỏi' })).toBeVisible()
  expect(state.records).toHaveLength(0)
  expect(state.calls.filter((call) => call.method === 'POST' && call.path === '/api/exams')).toHaveLength(0)
  expect(state.calls[0].payload).toEqual({ tenDeThi: 'Ôn tập về nước', khoi: '6', rawText: text, numQuestions: 2 })
  await page.getByLabel('Nội dung câu hỏi 1', { exact: true }).fill('Câu hỏi đã được giáo viên kiểm duyệt')
  await page.getByLabel('Chọn B là đáp án đúng cho câu 1', { exact: true }).check()
  await page.screenshot({ path: '../docs/screenshots/task_2_3/review-edit.png', fullPage: true })
  await page.getByRole('button', { name: 'Lưu vào kho đề', exact: true }).click()
  await expect(page).toHaveURL(/\/exams\/saved-exam$/)
  await expect(page.getByRole('status')).toContainText('Đã lưu đề thi vào kho.')
  const writes = state.calls.filter((call) => call.method === 'POST' && call.path === '/api/exams')
  expect(writes).toHaveLength(1)
  expect(writes[0].payload.cauHoiList[0]).toMatchObject({ noiDung: 'Câu hỏi đã được giáo viên kiểm duyệt', dapAnDung: 'B' })
  expect(Object.keys(writes[0].payload.cauHoiList[0]).sort()).toEqual(['noiDung', 'dapAnA', 'dapAnB', 'dapAnC', 'dapAnD', 'dapAnDung'].sort())
  await page.goto('/exams')
  await expect(page.getByRole('heading', { name: 'Ôn tập về nước', exact: true })).toBeVisible()
  await page.screenshot({ path: '../docs/screenshots/task_2_3/exam-library.png', fullPage: true })
  expect(errors).toEqual([])
})

test('UTF-8 text upload populates source and invalid files preserve it', async ({ page }) => {
  await mockExams(page)
  await page.goto('/exams/create-ai')
  await page.getByLabel('Tệp văn bản (.txt)', { exact: true }).setInputFiles({ name: 'bai-hoc.txt', mimeType: 'text/plain', buffer: Buffer.from(text) })
  await expect(page.getByLabel('Văn bản tài liệu', { exact: true })).toHaveValue(text)
  await page.getByLabel('Tệp văn bản (.txt)', { exact: true }).setInputFiles({ name: 'wrong.pdf', mimeType: 'application/pdf', buffer: Buffer.from('invalid') })
  await expect(page.getByRole('alert')).toContainText('Vui lòng chọn tệp văn bản .txt.')
  await expect(page.getByLabel('Văn bản tài liệu', { exact: true })).toHaveValue(text)
  await page.getByLabel('Tệp văn bản (.txt)', { exact: true }).setInputFiles({ name: 'large.txt', mimeType: 'text/plain', buffer: Buffer.alloc(1024 * 1024 + 1, 'a') })
  await expect(page.getByRole('alert')).toContainText('tối đa 1 MB')
  await page.getByLabel('Tệp văn bản (.txt)', { exact: true }).setInputFiles({ name: 'encoding.txt', mimeType: 'text/plain', buffer: Buffer.from([255, 255]) })
  await expect(page.getByRole('alert')).toContainText('Không đọc được tệp UTF-8')
})

test('invalid generation input does not call AI', async ({ page }) => {
  const state = await mockExams(page)
  await page.goto('/exams/create-ai')
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(page.getByText('Vui lòng nhập tên đề thi.')).toBeVisible()
  await expect(page.getByText('Vui lòng nhập tài liệu hoặc chọn một tệp văn bản.')).toBeVisible()
  await fillSource(page)
  await page.getByLabel('Số lượng câu hỏi', { exact: true }).fill('0')
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(page.getByText('Số câu hỏi phải là số nguyên từ 1 đến 50.')).toBeVisible()
  expect(state.calls).toHaveLength(0)
})

test('ERR_01 timeout is displayed and the source remains available for retry', async ({ page }) => {
  const state = await mockExams(page, { previewStatus: 503, records: [] })
  await page.goto('/exams/create-ai')
  await fillSource(page)
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('AI chưa trả kết quả trong 15 giây')
  await expect(page.getByLabel('Văn bản tài liệu', { exact: true })).toHaveValue(text)
  expect(state.records).toHaveLength(0)
  state.previewStatus = 200
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Xem trước & chỉnh sửa câu hỏi' })).toBeVisible()
})

test('question validation blocks saving blank questions or duplicate options', async ({ page }) => {
  const state = await mockExams(page, { records: [] })
  await page.goto('/exams/create-ai')
  await fillSource(page)
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await page.getByLabel('Nội dung câu hỏi 1', { exact: true }).fill(' ')
  await page.getByRole('button', { name: 'Lưu vào kho đề', exact: true }).click()
  await expect(page.getByText('Nội dung không được để trống.')).toBeVisible()
  await page.getByLabel('Nội dung câu hỏi 1', { exact: true }).fill('Câu hỏi hợp lệ')
  await page.getByLabel('Phương án B — Câu 1', { exact: true }).fill('h2o')
  await page.getByRole('button', { name: 'Lưu vào kho đề', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Bốn phương án phải khác nhau.')
  expect(state.records).toHaveLength(0)
})

test('review draft survives reload without another generation request', async ({ page }) => {
  const state = await mockExams(page, { records: [] })
  await page.goto('/exams/create-ai')
  await fillSource(page)
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await page.getByLabel('Nội dung câu hỏi 1', { exact: true }).fill('Nội dung bản nháp')
  await page.reload()
  await expect(page.getByLabel('Nội dung câu hỏi 1', { exact: true })).toHaveValue('Nội dung bản nháp')
  expect(state.calls.filter((call) => call.path.endsWith('/preview-ai'))).toHaveLength(1)
  expect(state.records).toHaveLength(0)
})

test('library search, detail and update work with whitelisted question payloads', async ({ page }) => {
  const state = await mockExams(page)
  await page.goto('/exams')
  await expect(page.getByRole('heading', { name: 'Kiến thức về nước', exact: true })).toBeVisible()
  await page.getByLabel('Tìm đề thi theo tên').fill('no match')
  await expect(page.getByText('Không có đề thi phù hợp.')).toBeVisible()
  await page.getByRole('button', { name: 'Xóa tìm kiếm' }).click()
  await page.getByRole('link', { name: 'Xem đề Kiến thức về nước', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Công thức hóa học của nước là gì?' })).toBeVisible()
  await page.getByRole('link', { name: 'Chỉnh sửa đề', exact: true }).click()
  await page.getByLabel('Tên đề thi', { exact: true }).fill('Đề đã sửa')
  await page.getByLabel('Chọn C là đáp án đúng cho câu 1', { exact: true }).check()
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page).toHaveURL(/\/exams\/exam-one$/)
  await expect(page.getByRole('heading', { name: 'Đề đã sửa', exact: true })).toBeVisible()
  const update = state.calls.find((call) => call.method === 'PUT')
  expect(update.payload.cauHoiList[0].dapAnDung).toBe('C')
  expect(update.payload.cauHoiList[0].idCauHoi).toBeUndefined()
})

test('delete requires confirmation and successful response removes the card', async ({ page }) => {
  const state = await mockExams(page)
  await page.goto('/exams')
  await page.getByRole('button', { name: 'Xóa đề Kiến thức về nước', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Hủy', exact: true }).click()
  expect(state.calls.filter((call) => call.method === 'DELETE')).toHaveLength(0)
  await page.getByRole('button', { name: 'Xóa đề Kiến thức về nước', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Xóa đề thi', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Kho đề đang trống' })).toBeVisible()
  expect(state.records).toHaveLength(0)
})

test('delete conflict keeps the exam and displays the server restriction', async ({ page }) => {
  const state = await mockExams(page, { deleteStatus: 409 })
  await page.goto('/exams')
  await page.getByRole('button', { name: 'Xóa đề Kiến thức về nước', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Xóa đề thi', exact: true }).click()
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('không thể sửa hoặc xóa')
  expect(state.records).toHaveLength(1)
})

test('failed update keeps teacher edits intact', async ({ page }) => {
  await mockExams(page, { updateStatus: 409 })
  await page.goto('/exams/exam-one/edit')
  await page.getByLabel('Tên đề thi', { exact: true }).fill('Chỉnh sửa chưa lưu')
  await page.getByRole('button', { name: 'Lưu thay đổi', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('không thể sửa hoặc xóa')
  await expect(page.getByLabel('Tên đề thi', { exact: true })).toHaveValue('Chỉnh sửa chưa lưu')
})

test('empty, retryable library failure and missing detail have clear states', async ({ page }) => {
  const state = await mockExams(page, { listStatus: 503, records: [] })
  await page.goto('/exams')
  await expect(page.getByRole('alert')).toContainText('Máy chủ đang gặp sự cố')
  state.listStatus = 200
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Kho đề đang trống' })).toBeVisible()
  await page.goto('/exams/missing')
  await expect(page.getByRole('alert')).toContainText('Không tìm thấy đề thi')
})

test('private exam routes redirect anonymous visitors', async ({ page }) => {
  await page.addInitScript(() => localStorage.removeItem('quizAi.teacherAccessToken'))
  const state = await mockExams(page)
  for (const path of ['/exams', '/exams/create-ai', '/exams/exam-one', '/exams/exam-one/edit']) {
    await page.goto(path)
    await expect(page).toHaveURL(/\/login$/)
  }
  expect(state.calls).toHaveLength(0)
})

test('mobile library and editor have no horizontal overflow', async ({ page }) => {
  await mockExams(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/exams')
  await expect(page.getByRole('heading', { name: 'Kiến thức về nước', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_2_3/library-mobile.png', fullPage: true })
  await page.goto('/exams/exam-one/edit')
  await expect(page.getByLabel('Nội dung câu hỏi 1', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: '../docs/screenshots/task_2_3/editor-mobile.png', fullPage: true })
})
