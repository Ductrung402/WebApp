import { AlertCircle, CheckCircle2 } from 'lucide-react'

export default function FormMessage({ children, success = false }) {
  if (!children) return null
  const Icon = success ? CheckCircle2 : AlertCircle
  return (
    <div role={success ? 'status' : 'alert'} className={`flex items-start gap-2 rounded-xl border p-3 text-sm leading-6 ${success ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-rose-200 bg-rose-50 text-rose-800'}`}>
      <Icon size={18} className="mt-1 shrink-0" aria-hidden="true" /><span>{children}</span>
    </div>
  )
}
