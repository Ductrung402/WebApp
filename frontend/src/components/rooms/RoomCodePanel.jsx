import { useEffect, useRef, useState } from 'react'
import { Check, Copy } from 'lucide-react'

export default function RoomCodePanel({ code }) {
  const [message, setMessage] = useState('')
  const [copied, setCopied] = useState(false)
  const alive = useRef(true)
  const timer = useRef(null)
  useEffect(() => {
    alive.current = true
    setMessage('')
    setCopied(false)
    return () => { alive.current = false; window.clearTimeout(timer.current) }
  }, [code])

  async function copyCode() {
    try {
      await navigator.clipboard.writeText(code)
      if (!alive.current) return
      setCopied(true)
      setMessage('Đã sao chép mã phòng.')
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => { setCopied(false); setMessage('') }, 3000)
    } catch {
      if (alive.current) {
        window.clearTimeout(timer.current)
        setCopied(false)
        setMessage('Không thể sao chép tự động. Bạn có thể chọn mã phòng và sao chép thủ công.')
      }
    }
  }

  return (
    <section aria-labelledby="room-code-title" className="rounded-2xl bg-brand-800 px-5 py-8 text-center text-white shadow-sm sm:px-8 sm:py-10">
      <h2 id="room-code-title" className="text-xs font-semibold uppercase tracking-[0.2em] text-brand-400">Mã phòng thi</h2>
      <p data-testid="room-code" aria-label={`Mã phòng thi ${code}`} className="my-6 pl-[0.15em] font-mono text-5xl font-bold tracking-[0.15em] sm:my-8 sm:text-7xl">{code}</p>
      <button type="button" onClick={copyCode} className="mt-5 inline-flex items-center justify-center gap-2 rounded-xl border border-white/30 bg-white/10 px-4 py-2.5 text-sm font-semibold hover:bg-white/20 focus-visible:outline-brand-400">
        {copied ? <Check size={17} aria-hidden="true" /> : <Copy size={17} aria-hidden="true" />}{copied ? 'Đã sao chép' : 'Sao chép mã'}
      </button>
      <p role="status" className="mt-3 min-h-5 text-xs leading-5 text-brand-100">{message}</p>
    </section>
  )
}
