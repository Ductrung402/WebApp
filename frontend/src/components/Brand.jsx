import { Sparkles } from 'lucide-react'

export default function Brand() {
  return (
    <span className="inline-flex items-center gap-3">
      <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-brand-700 text-white"><Sparkles size={22} aria-hidden="true" /></span>
      <span className="text-xl font-bold tracking-tight text-slate-900">Quiz<span className="text-brand-700"> AI</span></span>
    </span>
  )
}
