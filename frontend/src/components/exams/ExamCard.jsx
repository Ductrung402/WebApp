import { CalendarDays, DoorOpen, Eye, FileQuestion, Pencil, Trash2 } from 'lucide-react'
import { Link } from 'react-router-dom'
import { formatExamDate } from '../../utils/examValidation.js'

export default function ExamCard({ exam, onDelete }) {
  return (
    <article className="flex min-w-0 flex-col rounded-2xl border border-slate-200 bg-white p-5 shadow-sm transition-shadow hover:shadow-md">
      <div className="flex items-center justify-between"><span className="rounded-xl bg-brand-50 p-3 text-brand-700"><FileQuestion size={23} aria-hidden="true" /></span><span className="rounded-full bg-slate-100 px-3 py-1 text-xs text-slate-600">{exam.khoi ? `Khối ${exam.khoi}` : 'Không phân khối'}</span></div>
      <h2 className="mt-5 break-words text-lg font-semibold leading-6">{exam.tenDeThi}</h2>
      <div className="mb-5 mt-4 flex flex-wrap gap-4 text-xs text-slate-600">
        <span>{exam.soLuongCauHoi ?? '—'} câu hỏi</span><span className="inline-flex items-center gap-1.5"><CalendarDays size={14} aria-hidden="true" />{formatExamDate(exam.ngayTao)}</span>
      </div>
      <div className="mt-auto flex items-center gap-2 border-t border-slate-100 pt-4">
        <Link to={`/exams/${encodeURIComponent(exam.idDeThi)}`} className="button-secondary !px-3" aria-label={`Xem đề ${exam.tenDeThi}`}><Eye size={15} aria-hidden="true" /> Xem</Link>
        <Link to={`/exams/${encodeURIComponent(exam.idDeThi)}/edit`} className="button-secondary !px-3" aria-label={`Sửa đề ${exam.tenDeThi}`}><Pencil size={15} aria-hidden="true" /> Sửa</Link>
        <button type="button" onClick={() => onDelete(exam)} className="ml-auto rounded-lg p-2.5 text-slate-600 hover:bg-rose-50 hover:text-rose-700" aria-label={`Xóa đề ${exam.tenDeThi}`}><Trash2 size={17} aria-hidden="true" /></button>
      </div>
      {exam.soLuongCauHoi > 0 && <Link to={`/rooms/create?examId=${encodeURIComponent(exam.idDeThi)}`} className="mt-4 inline-flex items-center gap-2 text-sm font-semibold text-brand-700 hover:text-brand-800" aria-label={`Tạo phòng từ đề ${exam.tenDeThi}`}><DoorOpen size={16} aria-hidden="true" /> Tạo phòng thi</Link>}
    </article>
  )
}
