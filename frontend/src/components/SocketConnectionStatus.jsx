export default function SocketConnectionStatus({ status, error, retry, roomLabel = 'phòng chờ' }) {
  if (status === 'idle') return null
  return (
    <div data-testid="socket-status" aria-live="polite" className="flex flex-wrap items-center gap-2 text-xs leading-5 text-slate-600">
      <span className={`h-2 w-2 shrink-0 rounded-full ${status === 'joined' ? 'bg-emerald-500' : 'bg-amber-500'}`} aria-hidden="true" />
      <span>{status === 'closed' ? 'Phòng thi đã đóng' : status === 'joined' ? `Đã kết nối ${roomLabel}` : status === 'error' ? error : status === 'recovering' ? 'Đang khôi phục trạng thái bài làm…' : status === 'disconnected' ? `Đang kết nối lại với ${roomLabel}…` : `Đang kết nối với ${roomLabel}…`}</span>
      {status === 'error' && <button type="button" onClick={retry} className="font-semibold text-brand-700 hover:underline">Thử kết nối lại</button>}
    </div>
  )
}
