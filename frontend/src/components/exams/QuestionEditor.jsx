import { Trash2 } from 'lucide-react'
import { ANSWER_KEYS } from '../../utils/examValidation.js'

export default function QuestionEditor({ question, index, onChange, onRemove, canRemove, disabled, errors = {} }) {
  const number = index + 1
  return (
    <section className="rounded-2xl border border-slate-200 bg-white p-5 sm:p-6" aria-labelledby={`question-title-${number}`}>
      <div className="mb-5 flex items-center justify-between">
        <h3 id={`question-title-${number}`} className="font-semibold">Câu {number}</h3>
        <button type="button" onClick={onRemove} disabled={disabled || !canRemove} aria-label={`Xóa câu ${number}`} className="rounded-lg p-2 text-slate-400 hover:bg-rose-50 hover:text-rose-700 disabled:opacity-30"><Trash2 size={17} aria-hidden="true" /></button>
      </div>
      <label htmlFor={`question-${number}`} className="mb-2 block text-sm font-medium text-slate-700">Nội dung câu hỏi {number}</label>
      <textarea id={`question-${number}`} className="input-field min-h-24 resize-y" value={question.noiDung} onChange={(event) => onChange('noiDung', event.target.value)} disabled={disabled} aria-invalid={Boolean(errors.noiDung)} aria-describedby={errors.noiDung ? `question-error-${number}` : undefined} />
      {errors.noiDung && <p id={`question-error-${number}`} className="mt-1 text-xs text-rose-700">{errors.noiDung}</p>}
      <fieldset className="mt-5" disabled={disabled}>
        <legend className="mb-3 text-sm font-medium text-slate-700">Các phương án · chọn đáp án đúng</legend>
        <div className="grid gap-3 sm:grid-cols-2">
          {ANSWER_KEYS.map((key) => {
            const field = `dapAn${key}`
            const correct = question.dapAnDung === key
            return (
              <div key={key} className={`rounded-xl border p-3 ${correct ? 'border-emerald-300 bg-emerald-50/60' : 'border-slate-200'}`}>
                <div className="mb-2 flex items-center justify-between">
                  <label htmlFor={`answer-${number}-${key}`} className="text-sm font-semibold">Phương án {key}<span className="sr-only"> — Câu {number}</span></label>
                  <label className="flex cursor-pointer items-center gap-2 text-xs text-slate-600">
                    <input type="radio" name={`correct-${number}`} value={key} checked={correct} onChange={() => onChange('dapAnDung', key)} aria-label={`Chọn ${key} là đáp án đúng cho câu ${number}`} className="h-4 w-4 accent-emerald-700" />
                    <span>Đúng</span>
                  </label>
                </div>
                <input id={`answer-${number}-${key}`} className="input-field !bg-white" value={question[field]} onChange={(event) => onChange(field, event.target.value)} aria-invalid={Boolean(errors[field])} aria-describedby={errors[field] ? `answer-error-${number}-${key}` : undefined} />
                {errors[field] && <p id={`answer-error-${number}-${key}`} className="mt-1 text-xs text-rose-700">{errors[field]}</p>}
              </div>
            )
          })}
        </div>
        {(errors.options || errors.dapAnDung) && <p role="alert" className="mt-3 text-sm text-rose-700">{errors.options || errors.dapAnDung}</p>}
      </fieldset>
    </section>
  )
}
