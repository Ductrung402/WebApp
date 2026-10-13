import { useEffect, useState } from 'react'
import { CheckCircle2, Home, LoaderCircle, RefreshCw, Trophy } from 'lucide-react'
import { Link, Navigate, useLocation, useParams } from 'react-router-dom'
import FormMessage from '../components/FormMessage.jsx'
import { useStudentSession } from '../contexts/StudentSessionContext.jsx'
import { getStudentResult, resultError } from '../services/resultService.js'
import { formatScore, parseSubmissionResult, RESULT_MESSAGES } from '../utils/results.js'
import { normalizeRoomCode } from '../utils/studentValidation.js'

export default function StudentResult() {
  const { code } = useParams()
  const { session } = useStudentSession()
  const location = useLocation()
  const normalized = normalizeRoomCode(code)
  const valid = /^[A-Z0-9]{6}$/.test(normalized) && session?.maCodePhongThi === normalized
  const identity = `${session?.idHs}:${session?.idPhongThi}:${normalized}`
  const initial = location.state?.idPhongThi === session?.idPhongThi && location.state?.code === normalized ? parseSubmissionResult(location.state?.result, session?.idHs) : null
  const [state, setState] = useState({ identity, result: initial, loading: !initial, error: '' })
  const [version, setVersion] = useState(0)
  useEffect(() => {
    if (!valid) return
    const request = new AbortController()
    let disposed = false
    setState((current) => ({ identity, result: current.identity === identity ? current.result : null, loading: true, error: '' }))
    getStudentResult(session, request.signal).then((result) => {
      if (!disposed) setState({ identity, result, loading: false, error: '' })
    }).catch((error) => {
      if (!disposed && !request.signal.aborted) setState((current) => ({ ...current, loading: false, result: [404, 409, 401, 403].includes(error.response?.status) ? null : current.result, error: resultError(error) }))
    })
    return () => { disposed = true; request.abort() }
  }, [identity, valid, version])
  if (!valid) return <Navigate to="/join" replace state={{ notice: 'Hãy vào phòng thi để xem kết quả của bạn.' }} />
  if (code !== normalized) return <Navigate to={`/exam-room/${normalized}/result`} replace />
  const current = state.identity === identity ? state : { result: null, loading: true, error: '' }
  const result = current.result
  const message = result && RESULT_MESSAGES.find((item) => result.diemSo >= item.minimum)
  return (
    <section className="mx-auto w-full max-w-2xl overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-sm" aria-labelledby="student-result-title">
      <div className="border-b border-slate-100 px-5 py-6 sm:px-9"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Kết quả bài thi · {normalized}</p><h1 id="student-result-title" className="mt-3 text-2xl font-bold tracking-tight">Kết quả của bạn</h1><p className="mt-3 break-words text-sm text-slate-500">{session.hoVaTen} · {session.tenLop}</p></div>
      <div className="px-5 py-9 text-center sm:px-9 sm:py-11">
        {current.error && <div className="mb-6 text-left"><FormMessage>{current.error}</FormMessage><button type="button" disabled={current.loading} onClick={() => setVersion((value) => value + 1)} className="button-secondary mt-3"><RefreshCw size={16} aria-hidden="true" />Tải lại kết quả</button></div>}
        {!result ? current.loading ? <p role="status" className="flex items-center justify-center gap-3 py-10 text-slate-500"><LoaderCircle size={22} className="animate-spin" aria-hidden="true" />Đang tải kết quả…</p> : <p className="py-8 text-sm text-slate-500">Chưa có điểm để hiển thị.</p> : <>
          <div className="mx-auto flex h-20 w-20 items-center justify-center rounded-full bg-sky-50 text-brand-700"><Trophy size={36} aria-hidden="true" /></div>
          <p className="mt-7 flex flex-wrap items-baseline justify-center gap-3" aria-label={`Điểm ${formatScore(result.diemSo)} trên 10`}><span data-testid="result-score" className="text-6xl font-bold tracking-tight text-brand-700 sm:text-7xl">{formatScore(result.diemSo)}</span><span className="text-xl font-medium text-slate-400">/ 10</span></p>
          <p data-testid="result-correct" className="mt-5 inline-flex items-center gap-2 rounded-full bg-emerald-50 px-4 py-2 text-sm font-semibold text-emerald-700"><CheckCircle2 size={17} aria-hidden="true" />{result.soCauDung}/{result.tongSoCau} câu đúng</p>
          <h2 className="mt-8 text-xl font-semibold">{message.title}</h2><p className="mx-auto mt-3 max-w-md text-sm leading-7 text-slate-500">{message.text}</p>
          {current.loading && <p role="status" className="mt-4 text-xs text-slate-400">Đang xác nhận lại kết quả…</p>}
        </>}
        <Link to="/" state={{ endStudentSession: true }} className="button-primary mt-8 inline-flex"><Home size={17} aria-hidden="true" />Quay về trang chủ</Link>
      </div>
    </section>
  )
}
