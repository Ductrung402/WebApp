export default function RoomLoading({ label = 'Đang tải phòng thi' }) {
  return (
    <div role="status" aria-label={label} className="space-y-5">
      <span className="sr-only">{label}…</span>
      <div aria-hidden="true" className="animate-pulse rounded-2xl border border-slate-200 bg-white p-6">
        <div className="h-4 w-1/3 rounded bg-slate-100" /><div className="mt-6 h-20 rounded-xl bg-slate-100" />
      </div>
      <div aria-hidden="true" className="animate-pulse rounded-2xl border border-slate-200 bg-white p-6">
        {[0, 1, 2].map((row) => <div key={row} className="my-4 h-10 rounded-xl bg-slate-100" />)}
      </div>
    </div>
  )
}
