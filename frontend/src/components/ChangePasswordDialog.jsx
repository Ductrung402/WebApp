import { useEffect, useRef, useState } from 'react'
import { KeyRound, LoaderCircle, X } from 'lucide-react'
import FormField from './FormField.jsx'
import FormMessage from './FormMessage.jsx'
import { changePassword } from '../services/authService.js'
import { getAuthError, validatePasswordForm } from '../utils/authValidation.js'
import { getAccessToken } from '../utils/tokenStorage.js'

const emptyValues = { currentPassword: '', newPassword: '', confirmPassword: '' }

export default function ChangePasswordDialog({ onClose, onSuccess }) {
  const [values, setValues] = useState(emptyValues)
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const dialog = useRef(null)
  const alive = useRef(true)
  const  submitLock = useRef(false)
  const request = useRef(null)

  useEffect(() => {
    alive.current = true
    const element = dialog.current
    if (!element.open) element.showModal()
    element.querySelector('input')?.focus()
    return () => {
      alive.current = false
      request.current?.abort()
      element.close()
    }
  }, [])

  function close() {
    if (submitLock.current) return
    dialog.current.close()
    onClose()
  }

  function change(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setErrors((current) => ({ ...current, [name]: '', ...(name === 'newPassword' ? { confirmPassword: '' } : {}) }))
    setError('')
  }

  async function submit(event) {
    event.preventDefault()
    if (submitLock.current) return
    const validation = validatePasswordForm(values)
    setErrors(validation)
    setError('')
    if (Object.keys(validation).length) return
    submitLock.current = true
    setSaving(true)
    const token = getAccessToken()
    request.current = new AbortController()
    try {
      await changePassword(values, request.current.signal)
      if (!alive.current || token !== getAccessToken()) return
      setValues(emptyValues)
      setErrors({})
      dialog.current.close()
      onSuccess()
    } catch (error) {
      if (alive.current && token === getAccessToken()) {
        setError(error.response?.status === 400 ? 'Mật khẩu hiện tại không chính xác.' : getAuthError(error))
      }
    } finally {
      submitLock.current = false
      if (alive.current) setSaving(false)
    }
  }

  function backdropClick(event) {
    if (event.target !== event.currentTarget) return
    const bounds = event.currentTarget.getBoundingClientRect()
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close()
  }

  function containFocus(event) {
    if (event.key !== 'Tab') return
    const controls = Array.from(dialog.current.querySelectorAll('button, input, select, textarea, a[href], [tabindex]'))
      .filter((element) => !element.matches(':disabled') && element.tabIndex >= 0 && element.getClientRects().length)
    if (!controls.length) {
      event.preventDefault()
      dialog.current.focus()
      return
    }
    const first = controls[0], last = controls.at(-1)
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <dialog ref={dialog} tabIndex={-1} aria-labelledby="change-password-title" onCancel={(event) => { event.preventDefault(); close() }} onClick={backdropClick} onKeyDown={containFocus}
      className="max-h-[calc(100dvh_-_2rem)] w-[calc(100%_-_2rem)] max-w-lg overflow-y-auto rounded-2xl border-0 bg-white p-6 text-slate-800 shadow-xl backdrop:bg-slate-900/40 sm:p-8">
      <div className="mb-6 flex items-center justify-between gap-3">
        <h2 id="change-password-title" className="inline-flex items-center gap-3 text-xl font-semibold"><KeyRound size={22} className="shrink-0 text-brand-700" aria-hidden="true" />Đổi mật khẩu</h2>
        <button type="button" onClick={close} disabled={saving} aria-label="Đóng hộp thoại đổi mật khẩu" className="rounded-lg p-2 text-slate-600 hover:bg-brand-50 disabled:opacity-50"><X size={20} aria-hidden="true" /></button>
      </div>
      <form onSubmit={submit} noValidate aria-busy={saving} className="space-y-5">
        <FormMessage>{error}</FormMessage>
        <fieldset disabled={saving} className="min-w-0 space-y-5">
          <FormField name="currentPassword" label="Mật khẩu hiện tại" type="password" autoComplete="current-password" required value={values.currentPassword} onChange={change} error={errors.currentPassword} />
          <FormField name="newPassword" label="Mật khẩu mới" type="password" autoComplete="new-password" required hint="Ít nhất 8 ký tự; tối đa 72 byte." value={values.newPassword} onChange={change} error={errors.newPassword} />
          <FormField name="confirmPassword" label="Xác nhận mật khẩu mới" type="password" autoComplete="new-password" required value={values.confirmPassword} onChange={change} error={errors.confirmPassword} />
        </fieldset>
        <div className="flex flex-wrap justify-end gap-3 border-t border-slate-100 pt-5">
          <button type="button" onClick={close} disabled={saving} className="button-secondary">Hủy</button>
          <button type="submit" disabled={saving} className="button-primary">{saving ? <LoaderCircle size={16} className="animate-spin" aria-hidden="true" /> : <KeyRound size={16} aria-hidden="true" />}{saving ? 'Đang cập nhật…' : 'Cập nhật mật khẩu'}</button>
        </div>
      </form>
    </dialog>
  )
}
