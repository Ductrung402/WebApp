import { useEffect, useRef } from 'react'
import { LoaderCircle, X } from 'lucide-react'
import FormMessage from './FormMessage.jsx'

export default function ConfirmDialog({ open, title, children, onClose, onConfirm, busy, error, confirmLabel = 'Xóa đề thi' }) {
  const dialogRef = useRef(null)
  useEffect(() => {
    if (open && dialogRef.current && !dialogRef.current.open) dialogRef.current.showModal()
  }, [open])
  if (!open) return null
  return (
    <dialog ref={dialogRef} aria-labelledby="confirm-title" onCancel={(event) => { event.preventDefault(); if (!busy) onClose() }}
      onClick={(event) => { if (event.target === event.currentTarget && !busy) onClose() }} className="w-[calc(100%_-_2rem)] max-w-md rounded-2xl border-0 bg-white p-6 shadow-xl backdrop:bg-slate-900/40">
      <div className="flex items-center justify-between gap-3"><h2 id="confirm-title" className="text-lg font-semibold">{title}</h2><button type="button" onClick={onClose} disabled={busy} aria-label="Đóng hộp thoại" className="rounded-lg p-1 text-slate-600"><X size={20} aria-hidden="true" /></button></div>
      <div className="my-4 text-sm leading-6 text-slate-600">{children}</div>
      <FormMessage>{error}</FormMessage>
      <div className="mt-6 flex justify-end gap-3"><button type="button" onClick={onClose} disabled={busy} autoFocus className="button-secondary">Hủy</button><button type="button" onClick={onConfirm} disabled={busy} className="button-primary !bg-rose-700 hover:!bg-rose-800">{busy && <LoaderCircle size={16} className="animate-spin" aria-hidden="true" />}{busy ? 'Đang xử lý…' : confirmLabel}</button></div>
    </dialog>
  )
}
