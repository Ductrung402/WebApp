import { ArrowLeft, CalendarDays, DoorOpen, Pencil } from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router-dom'
import FormMessage from '../components/FormMessage.jsx'
import ExamLoading from '../components/exams/ExamLoading.jsx'
import QuestionCard from '../components/exams/QuestionCard.jsx'
import useExamDetail from '../hooks/useExamDetail.js'
import { formatExamDate } from '../utils/examValidation.js'

export default function ExamDetailPage() {
  const { examId } = useParams()
  const location = useLocation()
  const { exam, loading, error, retry } = useExamDetail(examId)
  return (
    <>
      <Link to="/exams" className="mb-6 inline-flex items-center gap-2 text-sm font-medium text-slate-600 hover:text-brand-700"><ArrowLeft size={16} aria-hidden="true" /> Kho đề thi</Link>
      {location.state?.notice && <div className="mb-5"><FormMessage success>{location.state.notice}</FormMessage></div>}
      {loading ? <ExamLoading /> : error ? <div className="space-y-4"><FormMessage>{error}</FormMessage><button type="button" onClick={retry} className="button-secondary">Thử lại</button></div> : exam && (
        <>
          <div className="mb-7 flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0"><p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Chi tiết đề thi</p><h1 className="mt-3 break-words text-3xl font-bold tracking-tight">{exam.tenDeThi}</h1><div className="mt-4 flex flex-wrap gap-4 text-sm text-slate-600"><span>{exam.khoi ? `Khối ${exam.khoi}` : 'Không phân khối'}</span><span>{exam.cauHoiList.length} câu hỏi</span><span className="inline-flex items-center gap-2"><CalendarDays size={15} aria-hidden="true" />{formatExamDate(exam.ngayTao)}</span></div></div>
            <div className="flex flex-wrap gap-3"><Link to={`/rooms/create?examId=${encodeURIComponent(exam.idDeThi)}`} className="button-primary"><DoorOpen size={16} aria-hidden="true" /> Tạo phòng thi</Link><Link to={`/exams/${encodeURIComponent(exam.idDeThi)}/edit`} className="button-secondary"><Pencil size={16} aria-hidden="true" /> Chỉnh sửa đề</Link></div>
          </div>
          {exam.noiDungTaiLieu && <details className="mb-6 rounded-xl border border-slate-200 bg-white p-4"><summary className="cursor-pointer text-sm font-semibold text-slate-700">Xem tài liệu nguồn</summary><p className="mt-4 whitespace-pre-wrap break-words text-sm leading-7 text-slate-600">{exam.noiDungTaiLieu}</p></details>}
          <div className="space-y-5">{exam.cauHoiList.map((question, index) => <QuestionCard key={question.idCauHoi} question={question} index={index} />)}</div>
        </>
      )}
    </>
  )
}
