import { ArrowLeft, CheckCircle2, Clock3, Hourglass, School, UserRound } from 'lucide-react'
import { Link, Navigate, useParams } from 'react-router-dom'
import FormMessage from '../components/FormMessage.jsx'
import SocketConnectionStatus from '../components/SocketConnectionStatus.jsx'
import useExamSocket from '../hooks/useExamSocket.js'
import { useStudentSession } from '../contexts/StudentSessionContext.jsx'
import { normalizeRoomCode } from '../utils/studentValidation.js'

export default function StudentWaiting() {
  const { code } = useParams()
  const { session, storageError } = useStudentSession()
  const normalizedCode = normalizeRoomCode(code)
  const validCode = /^[A-Z0-9]{6}$/.test(normalizedCode)
  const connection = useExamSocket(validCode && session?.maCodePhongThi === normalizedCode ? { maCodePhongThi: normalizedCode, userType: 'student', idHs: session.idHs } : null)

  if (!validCode || !session || session.maCodePhongThi !== normalizedCode) {
    return <Navigate to="/join" replace state={{ code: validCode ? normalizedCode : '', notice: 'Chưa có phiên học sinh cho phòng này. Hãy nhập thông tin để vào phòng.' }} />
  }
  if (code !== normalizedCode) return <Navigate to={`/exam-room/${normalizedCode}/waiting`} replace />
  if (connection.studentState?.trangThai === 'HOAN_THANH') return <Navigate to={`/exam-room/${normalizedCode}/result`} replace />
  if (connection.exam) return <Navigate to={`/exam-room/${normalizedCode}/taking`} replace />

  const details = [
    { label: 'Thí sinh', value: session.hoVaTen, icon: UserRound },
    { label: 'Lớp / nhóm', value: session.tenLop, icon: School },
    { label: 'Thời gian làm bài', value: `${session.thoiGianLamBai} phút`, icon: Clock3 },
  ]

  return (
    <div className="mx-auto w-full max-w-2xl">
      <Link to="/join" className="mb-6 inline-flex items-center gap-2 text-sm font-medium text-slate-600 hover:text-brand-700"><ArrowLeft size={16} aria-hidden="true" /> Trang vào phòng</Link>
      {storageError && <div className="mb-5"><FormMessage>{storageError}</FormMessage></div>}
      <div className="mb-4"><SocketConnectionStatus {...connection} /></div>
      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm" aria-labelledby="student-waiting-title">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 px-5 py-5 sm:px-8">
          <span className="inline-flex items-center gap-2 text-sm font-semibold text-emerald-700"><CheckCircle2 size={18} aria-hidden="true" /> Đã vào phòng thi</span>
          <span className="rounded-lg bg-brand-50 px-3 py-2 font-mono text-lg font-bold tracking-widest text-brand-700" aria-label={`Mã phòng ${session.maCodePhongThi}`}>{session.maCodePhongThi}</span>
        </div>
        <div className="px-5 py-10 text-center sm:px-8 sm:py-12">
          <div className="relative mx-auto mb-7 flex h-20 w-20 items-center justify-center" aria-hidden="true">
            <span className={`absolute inset-0 rounded-full bg-brand-100 ${connection.closed ? '' : 'animate-pulse motion-reduce:animate-none'}`} />
            <span className="relative rounded-full bg-brand-50 p-5 text-brand-700"><Hourglass size={28} /></span>
          </div>
          <h1 id="student-waiting-title" className="text-2xl font-bold tracking-tight sm:text-3xl">{connection.closed ? 'Phòng thi đã kết thúc' : 'Phòng chờ'}</h1>
          <p role="status" className="mt-4 text-sm font-medium leading-7 text-brand-700 sm:text-base">{connection.closed ? 'Giáo viên đã đóng phòng thi.' : 'Đang đợi giáo viên bắt đầu bài thi...'}</p>
          {connection.closed && <p className="mt-3 text-sm leading-6 text-slate-600">Trở về trang vào phòng để tham gia buổi thi khác.</p>}
        </div>
        <dl className="divide-y divide-slate-100 border-t border-slate-100 px-5 sm:px-8">
          {details.map(({ label, value, icon: Icon }) => (
            <div key={label} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-5">
              <dt className="inline-flex items-center gap-2 text-sm text-slate-600"><Icon size={17} aria-hidden="true" />{label}</dt>
              <dd className="min-w-0 max-w-full break-words text-sm font-semibold text-slate-800 sm:max-w-[65%]">{value}</dd>
            </div>
          ))}
        </dl>
      </section>
    </div>
  )
}
