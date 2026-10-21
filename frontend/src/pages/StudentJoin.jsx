import { useEffect, useRef, useState } from 'react'
import { ArrowRight, CheckCircle2, DoorOpen, LoaderCircle } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import FormField from '../components/FormField.jsx'
import FormMessage from '../components/FormMessage.jsx'
import { useStudentSession } from '../contexts/StudentSessionContext.jsx'
import { joinStudentRoom } from '../services/studentService.js'
import { getStudentEntryError, normalizeRoomCode, validateStudentInput } from '../utils/studentValidation.js'

export default function StudentJoin() {
  const navigate = useNavigate()
  const location = useLocation()
  const { session, rememberSession, storageError, clearSession } = useStudentSession()
  const [values, setValues] = useState({ hoVaTen: '', maCodePhongThi: location.state?.code || '' })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [joining, setJoining] = useState(false)
  const alive = useRef(true)
  const request = useRef(null)
  const submitLock = useRef(false)

  useEffect(() => {
    // End the completed session after navigation; clearing it on the result page
    // would race its missing-session redirect with React Router transitions.
    if (location.state?.endStudentSession) clearSession()
  }, [location.state?.endStudentSession, clearSession])

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; request.current?.abort() }
  }, [])

  function update(field, value) {
    setValues((current) => ({ ...current, [field]: value }))
    setErrors((current) => ({ ...current, [field]: '' }))
    setError('')
  }

  async function submit(event) {
    event.preventDefault()
    if (submitLock.current) return
    const issues = validateStudentInput(values)
    setErrors(issues)
    if (Object.keys(issues).length) return
    const code = normalizeRoomCode(values.maCodePhongThi)
    if (session?.maCodePhongThi === code && session.hoVaTen === values.hoVaTen.trim()) {
      navigate(`/exam-room/${code}/waiting`)
      return
    }
    submitLock.current = true
    setJoining(true)
    setError('')
    request.current = new AbortController()
    try {
      const joined = await joinStudentRoom(values, request.current.signal)
      if (!alive.current) return
      const saved = rememberSession(joined)
      navigate(`/exam-room/${saved.maCodePhongThi}/waiting`, { replace: true })
    } catch (error) {
      if (alive.current) setError(getStudentEntryError(error))
    } finally {
      submitLock.current = false
      if (alive.current) setJoining(false)
    }
  }

  return (
    <div className="grid items-center gap-8 lg:grid-cols-[minmax(0,1fr)_440px] lg:gap-16">
      <section>
        <h1 className="text-3xl font-bold leading-tight tracking-tight text-slate-900 sm:text-4xl lg:text-5xl">Vào phòng thi</h1>
        <p className="mt-6 flex items-center gap-2 text-sm font-medium text-slate-600"><CheckCircle2 size={18} className="text-emerald-600" aria-hidden="true" /> Không cần tài khoản hoặc mật khẩu</p>
      </section>

      <section aria-labelledby="join-form-title" className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-8">
        <span className="inline-flex rounded-xl bg-brand-50 p-3 text-brand-700"><DoorOpen size={24} aria-hidden="true" /></span>
        <h2 id="join-form-title" className="mb-6 mt-4 text-xl font-semibold">Tham gia phòng thi</h2>
        {location.state?.notice && <div className="mb-5"><FormMessage>{location.state.notice}</FormMessage></div>}
        {storageError && <div className="mb-5"><FormMessage>{storageError}</FormMessage></div>}
        {session && (
          <div className="mb-6 rounded-xl border border-brand-100 bg-brand-50 p-4">
            <p className="break-words text-sm font-semibold text-slate-800">Tiếp tục với {session.hoVaTen}</p>
            <p className="mt-2 break-words text-xs text-slate-600">{session.tenLop} · Mã {session.maCodePhongThi}</p>
            <Link to={`/exam-room/${session.maCodePhongThi}/waiting`} className="mt-3 inline-flex items-center gap-2 text-sm font-semibold text-brand-700">Tiếp tục phòng chờ <ArrowRight size={16} aria-hidden="true" /></Link>
          </div>
        )}
        <form noValidate onSubmit={submit} className="space-y-5" aria-busy={joining}>
          {error && <FormMessage>{error}</FormMessage>}
          <fieldset disabled={joining} className="min-w-0 space-y-5">
            <FormField name="hoVaTen" label="Họ và tên" placeholder="Ví dụ: Nguyễn Văn An" autoComplete="name" value={values.hoVaTen} onChange={(event) => update('hoVaTen', event.target.value)} error={errors.hoVaTen} required />
            <FormField name="maCodePhongThi" label="Mã phòng thi" placeholder="Ví dụ: AB12CD" autoComplete="off" autoCapitalize="characters" spellCheck={false} maxLength={6} value={values.maCodePhongThi} onChange={(event) => update('maCodePhongThi', normalizeRoomCode(event.target.value))} error={errors.maCodePhongThi} required />
          </fieldset>
          <button type="submit" disabled={joining} className="button-primary w-full !py-3.5">{joining ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : <ArrowRight size={18} aria-hidden="true" />}{joining ? 'Đang vào phòng…' : 'Vào phòng thi'}</button>
          {joining && <p role="status" className="text-sm text-slate-600">Đang kiểm tra mã và đưa bạn vào phòng thi…</p>}
        </form>
      </section>
    </div>
  )
}
