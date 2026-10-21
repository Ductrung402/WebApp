import { createRequire } from 'node:module'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')
// Tailwind/PostCSS resolve their unchanged configuration relative to the Frontend cwd.
process.chdir(resolve(root, 'frontend'))
const require = createRequire(resolve(root, 'frontend/package.json'))
const { chromium, expect } = require('@playwright/test')
let input = ''
for await (const chunk of process.stdin) input += chunk
const { apiBase } = JSON.parse(input)
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(apiBase)) throw new Error('Only isolated loopback backend is allowed')
process.env.VITE_API_BASE_URL = apiBase
const { createServer } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/vite/dist/node/index.js')).href)
let vite, browser, stage = 'startup'
const result = { backend: 'Tomcat + isolated PostgreSQL', ai: 'explicit HTTP stub; live probe verified separately' }
try {
  vite = await createServer({ root: resolve(root, 'frontend'), logLevel: 'silent', server: { host: 'localhost', port: 5173, strictPort: true } })
  await vite.listen()
  stage = 'register-login'
  const email = `browser-${randomUUID()}@example.com`, password = 'synthetic-browser-password'
  const registered = await fetch(`${apiBase}/api/auth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, mat_khau: password, ho_ten: 'Browser Teacher' }) })
  if (registered.status !== 201) throw new Error('Unable to create isolated browser fixture')
  browser = await chromium.launch({ channel: 'chrome', headless: true })
  const page = await browser.newPage()
  const errors = []
  page.on('pageerror', () => errors.push('pageerror'))
  await page.goto('http://localhost:5173/login')
  await page.getByLabel('Email', { exact: true }).fill(email)
  await page.getByLabel('Mật khẩu', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  stage = 'txt-preview'
  await page.goto('http://localhost:5173/exams/create-ai')
  await page.getByLabel('Tên đề thi', { exact: true }).fill('Đề đã kiểm duyệt')
  await page.getByLabel('Khối lớp', { exact: true }).selectOption('6')
  await page.getByLabel('Số lượng câu hỏi', { exact: true }).fill('1')
  const source = 'Nước có công thức H2O. Nước sôi ở 100 độ C.'
  await page.getByLabel('Tệp văn bản (.txt)', { exact: true }).setInputFiles({ name: 'nguon.txt', mimeType: 'text/plain', buffer: Buffer.from(source, 'utf8') })
  await expect(page.getByLabel('Văn bản tài liệu', { exact: true })).toHaveValue(source)
  await page.getByRole('button', { name: 'Sinh đề bằng AI', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Xem trước & chỉnh sửa câu hỏi', exact: true })).toBeVisible()
  const noRecords = await page.evaluate(async (api) => {
    const token = localStorage.getItem('quizAi.teacherAccessToken')
    return (await (await fetch(`${api}/api/exams`, { headers: { Authorization: `Bearer ${token}` } })).json()).length === 0
  }, apiBase)
  if (!noRecords) throw new Error('Preview wrote an exam')
  stage = 'review-save'
  await page.getByLabel('Nội dung câu hỏi 1', { exact: true }).fill('Nội dung đã được giáo viên chỉnh sửa')
  await page.getByLabel('Chọn B là đáp án đúng cho câu 1', { exact: true }).check()
  await page.screenshot({ path: resolve(root, 'backend/target/verification/T3_3_preview.png'), fullPage: true })
  await page.getByRole('button', { name: 'Lưu vào kho đề', exact: true }).click()
  await expect(page).toHaveURL(/\/exams\/[a-f0-9-]{36}$/)
  const saved = await page.evaluate(async (api) => {
    const token = localStorage.getItem('quizAi.teacherAccessToken')
    const response = await fetch(`${api}/api${location.pathname}`, { headers: { Authorization: `Bearer ${token}` } })
    const data = await response.json()
    return data.cauHoiList?.length === 1 && data.cauHoiList[0].noiDung === 'Nội dung đã được giáo viên chỉnh sửa' && data.cauHoiList[0].dapAnDung === 'B'
  }, apiBase)
  if (!saved) throw new Error('Reviewed text/answer did not persist')
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Đề đã kiểm duyệt', exact: true })).toBeVisible()
  await page.screenshot({ path: resolve(root, 'backend/target/verification/T3_3_saved.png'), fullPage: true })
  if (errors.length) throw new Error('React page error')
  Object.assign(result, { status: 'PASS', txtUtf8: true, actualLogin: true, previewNoWrites: true, reviewedTextAndAnswerPersisted: true, reload: true, pageErrors: 0 })
} catch (error) {
  result.status = 'FAIL'
  result.stage = stage
  result.detail = 'Browser creation flow failed; inspect the local test trace without credentials.'
  process.exitCode = 1
} finally {
  if (browser) await browser.close()
  if (vite) await vite.close()
  result.browserAndViteClosed = true
  await writeFile(resolve(root, 'backend/target/verification/T3_3_browser.json'), JSON.stringify(result, null, 2), 'utf8')
  console.log(JSON.stringify({ status: result.status, browserAndViteClosed: true }))
}
