import { Clock3 } from 'lucide-react'
import { formatCountdown } from '../utils/examRun.js'

export default function ExamCountdown({ seconds }) {
  return (
    <div className={`rounded-xl border px-4 py-3 ${seconds !== null && seconds <= 60 ? 'border-rose-200 bg-rose-50 text-rose-800' : 'border-sky-100 bg-sky-50 text-brand-700'}`}>
      <p className="flex items-center gap-2 text-xs font-medium"><Clock3 size={15} aria-hidden="true" />Thời gian còn lại</p>
      <p role="timer" aria-live="off" aria-label="Thời gian còn lại" data-testid="exam-countdown" className="mt-2 font-mono text-3xl font-bold tabular-nums">{formatCountdown(seconds)}</p>
    </div>
  )
}
