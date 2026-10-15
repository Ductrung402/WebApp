import { useEffect, useRef } from 'react'
import { LoaderCircle, LockKeyhole } from 'lucide-react'
import FormMessage from '../FormMessage.jsx'

export default function CloseRoomDialog({ open, busy, error, pendingCount, onCancel, onConfirm }) {
  const dialog = useRef(null)
  useEffect(() => {
    if (open && !dialog.current.open) dialog.current.showModal()
    else if (!open && dialog.current.open) dialog.current.close()
  }, [open])
  return <dialog ref={dialog} onCancel={(event) => { if (busy) event.preventDefault(); else onCancel() }} onClose={onCancel} aria-labelledby="close-room-title" className="w-[calc(100%-40px)] max-w-md rounded-2xl border-0 p-6 text-slate-800 shadow-xl backdrop:bg-slate-900/40">
    <LockKeyhole size={25} className="mb-4 text-rose-600" aria-hidden="true" /><h2 id="close-room-title" className="text-xl font-bold">Đóng phòng thi?</h2><p className="mt-3 text-sm leading-7 text-slate-600">Phòng sẽ dừng nhận thí sinh và bài nộp mới. Kết quả đã chấm vẫn được giữ lại.</p>{pendingCount > 0 && <p className="mt-3 rounded-xl bg-amber-50 p-3 text-sm leading-6 text-amber-800">Còn {pendingCount} thí sinh chưa hoàn thành. Các bài chưa nộp sẽ chưa có điểm.</p>}<div className="mt-4"><FormMessage>{error}</FormMessage></div><div className="mt-6 flex flex-wrap justify-end gap-3"><button type="button" disabled={busy} onClick={onCancel} className="button-secondary">Tiếp tục theo dõi</button><button type="button" disabled={busy} onClick={onConfirm} className="button-primary !bg-rose-600 hover:!bg-rose-700">{busy && <LoaderCircle size={16} className="animate-spin" aria-hidden="true" />}{busy ? 'Đang đóng…' : 'Xác nhận đóng phòng'}</button></div>
  </dialog>
}
