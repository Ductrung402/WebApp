import { CheckCircle2 } from 'lucide-react'
import { ANSWER_KEYS } from '../../utils/examValidation.js'

export default function QuestionCard({ question, index }) {
  return (
    <article className="rounded-2xl border border-slate-200 bg-white p-5 sm:p-6">
      <p className="text-xs font-semibold uppercase tracking-wide text-brand-700">Câu {index + 1}</p>
      <h2 className="mt-3 whitespace-pre-wrap break-words font-semibold leading-7">{question.noiDung}</h2>
      <div className="mt-5 grid gap-3 sm:grid-cols-2">
        {ANSWER_KEYS.map((key) => (
          <div key={key} className={`flex items-start gap-3 rounded-xl border p-3 text-sm ${question.dapAnDung === key ? 'border-emerald-200 bg-emerald-50 text-emerald-900' : 'border-slate-200 text-slate-600'}`}>
            <span className="font-semibold">{key}.</span><span className="min-w-0 flex-1 whitespace-pre-wrap break-words">{question[`dapAn${key}`]}</span>
            {question.dapAnDung === key && <CheckCircle2 size={17} className="mt-0.5 shrink-0" aria-label="Đáp án đúng" />}
          </div>
        ))}
      </div>
    </article>
  )
}
