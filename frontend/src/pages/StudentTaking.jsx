import { LoaderCircle, Send } from 'lucide-react'
import { Navigate, useParams } from 'react-router-dom'
import ExamCountdown from '../components/ExamCountdown.jsx'
import FormMessage from '../components/FormMessage.jsx'
import SocketConnectionStatus from '../components/SocketConnectionStatus.jsx'
import { useStudentSession } from '../contexts/StudentSessionContext.jsx'
import useExamCountdown from '../hooks/useExamCountdown.js'
import useExamSocket from '../hooks/useExamSocket.js'
import useStudentExamAnswers from '../hooks/useStudentExamAnswers.js'
import { normalizeRoomCode } from '../utils/studentValidation.js'

export default function StudentTaking() {
  const { code } = useParams()
  const { session, storageError } = useStudentSession()
  const normalized = normalizeRoomCode(code)
  const valid = /^[A-Z0-9]{6}$/.test(normalized) && session?.maCodePhongThi === normalized
  const connection = useExamSocket(valid ? { maCodePhongThi: normalized, userType: 'student', idHs: session.idHs } : null)
  const recovering = connection.status !== 'joined' || !connection.recoveryReady
  const seconds = useExamCountdown(connection.exam, connection.clock, recovering)
  const draft = useStudentExamAnswers(session, connection, seconds)
  if (!valid) return <Navigate to="/join" replace state={{ notice: 'Hãy vào phòng thi trước khi làm bài.' }} />
  if (code !== normalized) return <Navigate to={`/exam-room/${normalized}/taking`} replace />
  if (draft.receipt || connection.studentState?.trangThai === 'HOAN_THANH') return <Navigate to={`/exam-room/${normalized}/result`} replace state={{ result: draft.result, code: normalized, idPhongThi: session.idPhongThi }} />
  if (connection.syncReady && !connection.exam) return <Navigate to={`/exam-room/${normalized}/waiting`} replace />
  const exam = connection.exam
  if (!exam) return <div className="mx-auto w-full max-w-2xl space-y-5"><SocketConnectionStatus {...connection} /><p role="status" className="rounded-2xl border border-slate-200 bg-white p-8 text-center text-slate-500">Đang tải đề thi và đồng bộ thời gian…</p></div>
  const locked = Boolean(connection.closed || recovering || connection.studentState?.trangThai === 'HOAN_THANH' || draft.pending || draft.receipt || seconds === 0 || seconds === null)
  const answered = Object.keys(draft.answers).length

  return (
    <div className="mx-auto w-full max-w-4xl">
      <header className="mb-6 grid gap-5 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:grid-cols-[minmax(0,1fr)_190px] sm:p-7">
        <div className="min-w-0"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Đang làm bài · {normalized}</p><h1 className="mt-3 break-words text-2xl font-bold tracking-tight">{exam.tenDeThi}</h1><p className="mt-3 break-words text-sm text-slate-500">{session.hoVaTen} · {exam.tenLop}</p><div className="mt-3"><SocketConnectionStatus {...connection} roomLabel="phòng thi" /></div></div>
        <ExamCountdown seconds={seconds} />
      </header>
      {storageError && <div className="mb-4"><FormMessage>{storageError}</FormMessage></div>}
      {draft.storageError && <div className="mb-4"><FormMessage>{draft.storageError}</FormMessage></div>}
      {connection.closed ? <p role="status" className="mb-5 rounded-xl border border-slate-200 bg-slate-100 p-4 text-sm leading-6 text-slate-700">Giáo viên đã đóng phòng thi. Bài chưa nộp chưa có điểm; đáp án đã chọn vẫn được giữ lại.</p> : recovering && !draft.receipt && <p role="status" className="mb-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900">Đang khôi phục kết nối. Đáp án đã chọn được giữ lại; thời gian còn lại sẽ cập nhật khi kết nối thành công.</p>}
      {connection.studentState?.trangThai === 'HOAN_THANH' && !draft.receipt && <p role="status" className="mb-5 rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800">Bài thi này đã hoàn thành.</p>}
        <>
          {draft.pending && <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900"><p>{draft.pending.reason === 'timeout' ? 'Đã hết giờ. Đang gửi bài nộp tự động.' : 'Câu trả lời đã được khóa để gửi bài nộp.'}</p><p className="mt-1">{draft.sending ? 'Đang chờ máy chủ xác nhận…' : 'Bài nộp được giữ cho đến khi máy chủ xác nhận.'}</p></div>}
          {draft.error && <div className="mb-5 space-y-3"><FormMessage>{draft.error}</FormMessage>{draft.pending && <button type="button" disabled={draft.sending || connection.status !== 'joined'} onClick={draft.retry} className="button-secondary">Thử gửi lại bài</button>}</div>}
          <p className="mb-5 text-sm text-slate-500">Đã chọn {answered}/{exam.cauHoiList.length} câu hỏi</p>
          <div className="space-y-5">
            {exam.cauHoiList.map((question, index) => (
              <fieldset key={question.idCauHoi} disabled={locked} className="min-w-0 rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-7">
                <legend className="sr-only">Câu {index + 1}</legend>
                <h2 className="mb-5 whitespace-pre-wrap break-words font-semibold leading-7"><span className="text-brand-700">Câu {index + 1}. </span>{question.noiDung}</h2>
                <div className="grid gap-3 sm:grid-cols-2">
                  {['A', 'B', 'C', 'D'].map((option) => {
                    const selected = draft.answers[question.idCauHoi] === option
                    return <label key={option} className={`flex min-w-0 cursor-pointer items-start gap-3 rounded-xl border p-4 text-sm leading-6 ${selected ? 'border-sky-300 bg-sky-50 text-brand-700' : 'border-slate-200 text-slate-700'} ${locked ? 'cursor-not-allowed opacity-70' : 'hover:border-sky-300'}`}><input type="radio" name={`answer-${question.idCauHoi}`} value={option} checked={selected} onChange={() => draft.choose(question.idCauHoi, option)} aria-label={`Câu ${index + 1}: ${option}. ${question[`dapAn${option}`]}`} className="mt-1 shrink-0 accent-sky-700" /><span className="min-w-0 break-words"><strong>{option}.</strong> {question[`dapAn${option}`]}</span></label>
                  })}
                </div>
              </fieldset>
            ))}
          </div>
          <div className="mt-6 flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-slate-200 bg-white p-5"><div><p className="text-sm font-medium">{exam.cauHoiList.length - answered} câu chưa chọn</p><p className="mt-2 text-xs leading-5 text-slate-500">Nộp bài sẽ kết thúc việc chỉnh sửa câu trả lời.</p></div><button type="button" disabled={locked || draft.sending} onClick={draft.submit} className="button-primary">{draft.sending ? <LoaderCircle size={17} className="animate-spin" aria-hidden="true" /> : <Send size={17} aria-hidden="true" />}Nộp bài</button></div>
        </>
    </div>
  )
}
