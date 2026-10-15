import { useCallback, useEffect, useRef, useState } from 'react'
import { FileText, Plus, RefreshCw, Search } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'
import ConfirmDialog from '../components/ConfirmDialog.jsx'
import FormMessage from '../components/FormMessage.jsx'
import ExamCard from '../components/exams/ExamCard.jsx'
import ExamLoading from '../components/exams/ExamLoading.jsx'
import { deleteExam, listExams } from '../services/examService.js'
import { getExamError } from '../utils/examValidation.js'

export default function ExamsPage() {
  const location = useLocation()
  const [exams, setExams] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [notice, setNotice] = useState(location.state?.notice || '')
  const [target, setTarget] = useState(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  const request = useRef(null)
  const alive = useRef(true)
  const load = useCallback(async () => {
    request.current?.abort()
    const controller = new AbortController()
    request.current = controller
    setLoading(true)
    setError('')
    try {
      const data = await listExams(controller.signal)
      if (!controller.signal.aborted) setExams(data)
    } catch (error) {
      if (!controller.signal.aborted) setError(getExamError(error))
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }, [])
  useEffect(() => {
    alive.current = true
    load()
    return () => { alive.current = false; request.current?.abort() }
  }, [load])
  async function confirmDelete() {
    if (deleting || !target) return
    setDeleting(true)
    setDeleteError('')
    try {
      await deleteExam(target.idDeThi)
      if (!alive.current) return
      setExams((current) => current.filter((exam) => exam.idDeThi !== target.idDeThi))
      setNotice('Đã xóa đề thi và các câu hỏi liên thuộc.')
      setTarget(null)
    } catch (error) {
      if (alive.current) setDeleteError(getExamError(error, 'delete'))
    } finally {
      if (alive.current) setDeleting(false)
    }
  }
  const matches = exams.filter((exam) => exam.tenDeThi.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()))
  return (
    <>
      <div className="mb-7 flex flex-wrap items-start justify-between gap-4">
        <div><p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Quản lý đề thi</p><h1 className="mt-3 text-3xl font-bold tracking-tight">Kho đề thi cá nhân</h1></div>
        <Link to="/exams/create-ai" className="button-primary"><Plus size={18} aria-hidden="true" /> Tạo đề bằng AI</Link>
      </div>
      {notice && <div className="mb-5"><FormMessage success>{notice}</FormMessage></div>}
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="relative w-full sm:max-w-sm"><Search size={17} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-600" aria-hidden="true" /><input aria-label="Tìm đề thi theo tên" type="search" className="input-field !pl-10" placeholder="Tìm đề thi theo tên…" value={query} onChange={(event) => setQuery(event.target.value)} /></div>
        {!loading && !error && <p className="text-sm text-slate-600">{exams.length} đề thi trong kho</p>}
      </div>
      {loading ? <ExamLoading /> : error ? <div className="space-y-4"><FormMessage>{error}</FormMessage><button type="button" onClick={load} className="button-secondary"><RefreshCw size={16} aria-hidden="true" /> Thử lại</button></div> : !exams.length ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-white px-6 py-16 text-center"><FileText size={40} className="mx-auto text-brand-600" aria-hidden="true" /><h2 className="mt-5 text-lg font-semibold">Kho đề đang trống</h2><p className="mt-3 text-sm text-slate-600">Bắt đầu từ tài liệu của bạn để tạo đề thi đầu tiên.</p><Link to="/exams/create-ai" className="button-primary mt-6">Tạo đề đầu tiên</Link></div>
      ) : !matches.length ? (
        <div className="rounded-2xl border border-slate-200 bg-white p-10 text-center"><p className="text-slate-600">Không có đề thi phù hợp.</p><button type="button" className="mt-4 text-sm font-semibold text-brand-700" onClick={() => setQuery('')}>Xóa tìm kiếm</button></div>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">{matches.map((exam) => <ExamCard key={exam.idDeThi} exam={exam} onDelete={(exam) => { setTarget(exam); setDeleteError('') }} />)}</div>
      )}
      <ConfirmDialog open={Boolean(target)} title="Xóa đề thi?" busy={deleting} error={deleteError} onClose={() => setTarget(null)} onConfirm={confirmDelete}>
        <p>Đề “{target?.tenDeThi}” cùng các câu hỏi sẽ bị xóa. Bạn có muốn tiếp tục?</p>
      </ConfirmDialog>
    </>
  )
}
