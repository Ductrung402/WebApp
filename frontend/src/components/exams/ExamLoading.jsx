export default function ExamLoading({ cards = 3 }) {
  return (
    <div role="status" aria-label="Đang tải đề thi" className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
      {Array.from({ length: cards }, (_, index) => <div key={index} aria-hidden="true" className="animate-pulse rounded-2xl border border-slate-200 bg-white p-6"><div className="h-10 w-10 rounded-xl bg-slate-100" /><div className="mt-5 h-4 w-4/5 rounded bg-slate-100" /><div className="mt-3 h-3 w-1/2 rounded bg-slate-100" /><div className="mt-8 h-8 rounded-lg bg-slate-100" /></div>)}
      <span className="sr-only">Đang tải đề thi…</span>
    </div>
  )
}
