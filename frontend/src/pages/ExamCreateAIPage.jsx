import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, Check } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import AiSourceForm from '../components/exams/AiSourceForm.jsx'
import ExamEditor from '../components/exams/ExamEditor.jsx'
import { useAuth } from '../contexts/AuthContext.jsx'
import { previewAiExam, saveNewExam } from '../services/examService.js'
import { getExamError, hasDraftErrors, validateAiInput, validateExamDraft } from '../utils/examValidation.js'
import { clearExamDraft, readExamDraft, writeExamDraft } from '../utils/examDraftStorage.js'

export default function ExamCreateAIPage() {
  const { teacher } = useAuth()
  const navigate = useNavigate()
  const [draft, setDraft] = useState(() => readExamDraft(teacher.idGv))
  const [values, setValues] = useState(() => ({
    tenDeThi: draft?.tenDeThi || '', khoi: draft?.khoi || '',
    numQuestions: String(draft?.cauHoiList.length || 10), rawText: draft?.noiDungTaiLieu || '',
  }))
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [saving, setSaving] = useState(false)
  const [fileName, setFileName] = useState('')
  const [fileError, setFileError] = useState('')
  const [reading, setReading] = useState(false)
  const [discardOpen, setDiscardOpen] = useState(false)
  const alive = useRef(true)
  const request = useRef(null)
  const fileVersion = useRef(0)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; request.current?.abort(); fileVersion.current += 1 }
  }, [])
  useEffect(() => { if (draft) writeExamDraft(teacher.idGv, draft) }, [draft, teacher.idGv])

  function changeField(name, value) {
    setValues((current) => ({ ...current, [name]: value }))
    setErrors((current) => ({ ...current, [name]: '' }))
    setError('')
  }

  async function readFile(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setFileError('')
    if (!file.name.toLowerCase().endsWith('.txt')) { setFileError('Vui lòng chọn tệp văn bản .txt.'); return }
    if (file.size > 1024 * 1024) { setFileError('Tệp quá lớn. Vui lòng chọn tệp tối đa 1 MB.'); return }
    const version = ++fileVersion.current
    setReading(true)
    try {
      const text = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer())
      if (!text.trim()) throw new Error('Tệp không chứa văn bản. Vui lòng chọn tệp khác.')
      if (version !== fileVersion.current || !alive.current) return
      changeField('rawText', text)
      setFileName(file.name)
    } catch (error) {
      if (alive.current && version === fileVersion.current) setFileError(error instanceof TypeError ? 'Không đọc được tệp UTF-8. Vui lòng lưu tệp dưới dạng UTF-8 rồi thử lại.' : error.message)
    } finally {
      if (alive.current && version === fileVersion.current) setReading(false)
    }
  }

  async function generate() {
    if (busy || reading) return
    const validation = validateAiInput(values)
    setErrors(validation)
    setError('')
    if (Object.keys(validation).length) return
    setBusy(true)
    const controller = new AbortController()
    request.current = controller
    try {
      const preview = await previewAiExam(values, controller.signal)
      if (alive.current && !controller.signal.aborted) { setDraft(preview); setErrors({}) }
    } catch (error) {
      if (alive.current && !controller.signal.aborted) setError(getExamError(error, 'generate'))
    } finally { if (alive.current && !controller.signal.aborted) setBusy(false) }
  }

  async function save() {
    if (saving) return
    const validation = validateExamDraft(draft)
    setErrors(validation)
    if (hasDraftErrors(validation)) return
    setSaving(true)
    setError('')
    try {
      const saved = await saveNewExam(draft)
      if (!alive.current) return
      clearExamDraft(teacher.idGv)
      navigate(`/exams/${encodeURIComponent(saved.idDeThi)}`, { replace: true, state: { notice: 'Đã lưu đề thi vào kho.' } })
    } catch (error) {
      if (alive.current) setError(getExamError(error, 'save'))
    } finally { if (alive.current) setSaving(false) }
  }

  function returnToSource() {
    setValues({ tenDeThi: draft.tenDeThi, khoi: draft.khoi, numQuestions: String(draft.cauHoiList.length), rawText: draft.noiDungTaiLieu || '' })
    clearExamDraft(teacher.idGv)
    setDraft(null)
    setError('')
    setErrors({})
    setDiscardOpen(false)
  }

  return (
    <>
      <Link to="/exams" className="mb-6 inline-flex items-center gap-2 text-sm font-medium text-slate-600 hover:text-brand-700"><ArrowLeft size={16} aria-hidden="true" /> Kho đề thi</Link>
      <p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">{draft ? 'Kiểm duyệt đề thi' : 'Tạo đề thi mới'}</p>
      <h1 className="mt-3 text-3xl font-bold tracking-tight">{draft ? 'Xem trước & chỉnh sửa câu hỏi' : 'Tạo đề thi bằng AI'}</h1>
      {draft && <p className="mt-3 text-xs font-medium text-slate-600">Bản nháp chưa lưu</p>}
      <ol aria-label="Các bước tạo đề" className="mb-7 mt-6 flex flex-wrap gap-5 text-xs font-medium">
        {['Nhập tài liệu', 'Kiểm duyệt câu hỏi', 'Lưu vào kho'].map((label, index) => <li key={label} className={`flex items-center gap-2 ${index === (draft ? 1 : 0) ? 'text-brand-700' : 'text-slate-600'}`}><span className={`flex h-6 w-6 items-center justify-center rounded-full ${index === (draft ? 1 : 0) ? 'bg-brand-700 text-white' : 'bg-slate-100'}`}>{draft && index === 0 ? <Check size={13} aria-hidden="true" /> : index + 1}</span>{label}</li>)}
      </ol>
      {draft ? (
        <ExamEditor draft={draft} onChange={(value) => { setDraft(value); setErrors({}); setError('') }} onSave={save} onCancel={() => setDiscardOpen(true)} saving={saving} error={error} errors={errors} isNew />
      ) : (
        <AiSourceForm values={values} onChange={changeField} onFileChange={readFile} fileName={fileName} fileError={fileError} errors={errors} error={error} busy={busy} reading={reading} onSubmit={generate} />
      )}
      <ConfirmDialog open={discardOpen} title="Quay lại tài liệu?" onClose={() => setDiscardOpen(false)} onConfirm={returnToSource} confirmLabel="Quay lại tài liệu">
        Các chỉnh sửa câu hỏi chưa lưu sẽ bị bỏ. Tài liệu nguồn được giữ lại.
      </ConfirmDialog>
    </>
  )
}
