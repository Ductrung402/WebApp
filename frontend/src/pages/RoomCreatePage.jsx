import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, Clock3, DoorOpen, LoaderCircle, RefreshCw } from 'lucide-react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import FormField from '../components/FormField.jsx'
import FormMessage from '../components/FormMessage.jsx'
import RoomLoading from '../components/rooms/RoomLoading.jsx'
import { listExams } from '../services/examService.js'
import { createRoom } from '../services/roomService.js'
import { getRoomError, validateRoomInput } from '../utils/roomValidation.js'

export default function RoomCreatePage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const requestedExamId = searchParams.get('examId') || ''
  const [values, setValues] = useState({ idDeThi: requestedExamId, tenLop: '', thoiGianLamBai: '30' })
  const [exams, setExams] = useState([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [error, setError] = useState('')
  const [errors, setErrors] = useState({})
  const [creating, setCreating] = useState(false)
  const [version, setVersion] = useState(0)
  const alive = useRef(true)
  const createRequest = useRef(null)
  const submitLock = useRef(false)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; createRequest.current?.abort() }
  }, [])
  useEffect(() => {
    setValues((current) => ({ ...current, idDeThi: requestedExamId }))
    setErrors({})
  }, [requestedExamId])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setLoadError('')
    listExams(controller.signal).then((data) => {
      if (controller.signal.aborted) return
      setExams(data)
      setValues((current) => ({ ...current, idDeThi: data.some((exam) => exam.idDeThi === current.idDeThi && exam.soLuongCauHoi > 0) ? current.idDeThi : '' }))
    }).catch((error) => {
      if (!controller.signal.aborted) setLoadError(getRoomError(error))
    }).finally(() => {
      if (!controller.signal.aborted) setLoading(false)
    })
    return () => controller.abort()
  }, [requestedExamId, version])

  function update(field, value) {
    setValues((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: '' }))
    setError('')
  }
  async function submit(event) {
    event.preventDefault()
    if (submitLock.current) return
    const issues = validateRoomInput(values, exams)
    setErrors(issues)
    if (Object.keys(issues).length) return
    submitLock.current = true
    setCreating(true)
    setError('')
    createRequest.current = new AbortController()
    try {
      const room = await createRoom(values, createRequest.current.signal)
      if (alive.current) navigate(`/rooms/${encodeURIComponent(room.idPhongThi)}/waiting`, { replace: true })
    } catch (error) {
      if (alive.current) setError(getRoomError(error, 'create'))
    } finally {
      submitLock.current = false
      if (alive.current) setCreating(false)
    }
  }
  const selected = exams.find((exam) => exam.idDeThi === values.idDeThi)
  const hasExams = exams.some((exam) => exam.soLuongCauHoi > 0)

  return (
    <>
      <Link to="/exams" className="mb-6 inline-flex items-center gap-2 text-sm font-medium text-slate-500 hover:text-brand-700"><ArrowLeft size={16} aria-hidden="true" /> Kho đề thi</Link>
      <p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Phòng thi</p>
      <h1 className="mt-3 text-3xl font-bold tracking-tight">Khởi tạo phòng thi</h1>
      <p className="mb-8 mt-3 text-sm leading-6 text-slate-500">Chọn một đề đã lưu và thiết lập buổi thi cho lớp của bạn.</p>
      {loading ? <RoomLoading label="Đang tải kho đề thi" /> : loadError ? (
        <div className="space-y-4"><FormMessage>{loadError}</FormMessage><button type="button" onClick={() => setVersion((value) => value + 1)} className="button-secondary">Thử lại</button></div>
      ) : !hasExams ? (
        <section className="rounded-2xl border border-dashed border-slate-300 bg-white px-6 py-12 text-center"><DoorOpen size={36} className="mx-auto text-brand-700" aria-hidden="true" /><h2 className="mt-5 text-lg font-semibold">Chưa có đề thi để tạo phòng</h2><p className="mt-3 text-sm leading-6 text-slate-500">Hãy lưu một đề thi có câu hỏi vào kho trước khi tạo phòng.</p><Link to="/exams/create-ai" className="button-primary mt-6">Tạo đề bằng AI</Link></section>
      ) : (
        <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_280px]">
          <form noValidate onSubmit={submit} className="space-y-6 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-7" aria-busy={creating}>
            {error && <FormMessage>{error}</FormMessage>}
            <fieldset disabled={creating} className="min-w-0 space-y-6">
              <div>
                <label htmlFor="idDeThi" className="mb-2 block text-sm font-medium text-slate-700">Đề thi</label>
                <select id="idDeThi" className="input-field" value={values.idDeThi} onChange={(event) => update('idDeThi', event.target.value)} aria-invalid={Boolean(errors.idDeThi)} aria-describedby={errors.idDeThi ? 'idDeThi-error' : undefined} required>
                  <option value="">Chọn đề thi từ kho của bạn</option>
                  {exams.map((exam) => <option key={exam.idDeThi} value={exam.idDeThi} disabled={!(exam.soLuongCauHoi > 0)}>{exam.tenDeThi} · {exam.soLuongCauHoi ?? 0} câu{exam.khoi ? ` · Khối ${exam.khoi}` : ''}</option>)}
                </select>
                {errors.idDeThi && <p id="idDeThi-error" className="mt-2 text-xs text-rose-700">{errors.idDeThi}</p>}
              </div>
              <FormField name="tenLop" label="Tên lớp / nhóm" placeholder="Ví dụ: Lớp 6A" value={values.tenLop} onChange={(event) => update('tenLop', event.target.value)} error={errors.tenLop} required />
              <FormField name="thoiGianLamBai" label="Thời gian làm bài (phút)" type="number" min="1" max="2147483647" step="1" value={values.thoiGianLamBai} onChange={(event) => update('thoiGianLamBai', event.target.value)} error={errors.thoiGianLamBai} required />
            </fieldset>
            <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-5">
              <button type="button" disabled={creating} onClick={() => setVersion((value) => value + 1)} className="text-sm font-medium text-slate-500 hover:text-brand-700 disabled:opacity-50"><RefreshCw size={14} className="mr-1.5 inline" aria-hidden="true" />Tải lại kho đề</button>
              <button type="submit" disabled={creating} className="button-primary">{creating ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : <DoorOpen size={18} aria-hidden="true" />}{creating ? 'Đang tạo phòng…' : 'Tạo phòng'}</button>
            </div>
            {creating && <p role="status" className="text-sm text-slate-500">Đang khởi tạo phòng thi và cấp mã phòng…</p>}
          </form>
          <aside className="rounded-2xl border border-sky-100 bg-sky-50/70 p-6">
            <Clock3 size={24} className="text-brand-700" aria-hidden="true" /><h2 className="mt-4 font-semibold">Sẵn sàng cho buổi thi</h2>
            <p className="mt-3 text-sm leading-6 text-slate-600">Sau khi tạo, bạn sẽ nhận được mã phòng gồm 6 ký tự để chia sẻ với học sinh.</p>
            {selected && <div className="mt-5 border-t border-sky-100 pt-4"><p className="break-words text-sm font-semibold">{selected.tenDeThi}</p><p className="mt-2 text-xs text-slate-500">{selected.soLuongCauHoi} câu hỏi{selected.khoi ? ` · Khối ${selected.khoi}` : ''}</p></div>}
          </aside>
        </div>
      )}
    </>
  )
}
