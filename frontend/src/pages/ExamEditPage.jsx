import { useEffect, useRef, useState } from 'react'
import { ArrowLeft } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import FormMessage from '../components/FormMessage.jsx'
import ExamEditor from '../components/exams/ExamEditor.jsx'
import ExamLoading from '../components/exams/ExamLoading.jsx'
import useExamDetail from '../hooks/useExamDetail.js'
import { toEditableExam, updateExam } from '../services/examService.js'
import { getExamError, hasDraftErrors, validateExamDraft } from '../utils/examValidation.js'

export default function ExamEditPage() {
  const { examId } = useParams()
  const navigate = useNavigate()
  const { exam, loading, error: loadError, retry } = useExamDetail(examId)
  const [draft, setDraft] = useState(null)
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const [dirty, setDirty] = useState(false)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => { if (exam) { setDraft(toEditableExam(exam)); setDirty(false); setError(''); setErrors({}) } }, [exam])
  useEffect(() => {
    function beforeUnload(event) { if (dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty])
  async function save() {
    if (saving) return
    const validation = validateExamDraft(draft)
    setErrors(validation)
    if (hasDraftErrors(validation)) return
    setSaving(true)
    setError('')
    try {
      await updateExam(examId, draft)
      if (!alive.current) return
      setDirty(false)
      navigate(`/exams/${encodeURIComponent(examId)}`, { replace: true, state: { notice: 'Đã lưu thay đổi đề thi.' } })
    } catch (error) {
      if (alive.current) setError(getExamError(error, 'save'))
    } finally { if (alive.current) setSaving(false) }
  }
  return (
    <>
      <Link to={`/exams/${encodeURIComponent(examId)}`} className="mb-6 inline-flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-brand-700"><ArrowLeft size={16} aria-hidden="true" /> Chi tiết đề thi</Link>
      <h1 className="text-3xl font-bold tracking-tight">Chỉnh sửa đề thi</h1><p className="mb-7 mt-3 text-sm text-slate-500">Rà soát nội dung và chọn đáp án đúng cho từng câu hỏi.</p>
      {loading ? <ExamLoading /> : loadError ? <div className="space-y-4"><FormMessage>{loadError}</FormMessage><button type="button" onClick={retry} className="button-secondary">Thử lại</button></div> : draft && (
        <ExamEditor draft={draft} onChange={(value) => { setDraft(value); setDirty(true); setErrors({}); setError('') }} onSave={save} onCancel={() => navigate(`/exams/${encodeURIComponent(examId)}`)} saving={saving} error={error} errors={errors} />
      )}
    </>
  )
}
