import { useState } from 'react'
import { ArrowRight, LoaderCircle } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import FormField from '../components/FormField.jsx'
import FormMessage from '../components/FormMessage.jsx'
import { useAuth } from '../contexts/AuthContext.jsx'
import AuthLayout from '../layouts/AuthLayout.jsx'
import { getAuthError, validateAuthForm } from '../utils/authValidation.js'

export default function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()
  const [values, setValues] = useState({ fullName: '', email: '', password: '', school: '', province: '' })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  function change(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setErrors((current) => ({ ...current, [name]: '' }))
    setError('')
  }

  async function submit(event) {
    event.preventDefault()
    if (busy) return
    const validation = validateAuthForm(values, true)
    setErrors(validation)
    setError('')
    if (Object.keys(validation).length) return
    setBusy(true)
    try {
      await register(values)
      navigate('/login', { replace: true, state: { registeredEmail: values.email.trim(), registrationSuccess: true } })
    } catch (error) {
      setError(getAuthError(error))
    } finally { setBusy(false) }
  }

  return (
    <AuthLayout>
      <p className="mb-3 text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Bắt đầu cùng EZam</p>
      <h1 className="text-3xl font-bold tracking-tight text-slate-900">Tạo tài khoản giáo viên</h1>
      <p className="mt-3 text-sm leading-6 text-slate-600">Tạo tài khoản EZam để soạn đề bằng AI và quản lý phòng thi của bạn.</p>
      <form onSubmit={submit} noValidate className="mt-7 space-y-4" aria-busy={busy}>
        <FormMessage>{error}</FormMessage>
        <FormField name="fullName" label="Họ và tên" autoComplete="name" required placeholder="Nguyễn Văn A" value={values.fullName} onChange={change} error={errors.fullName} disabled={busy} />
        <FormField name="email" label="Email" type="email" autoComplete="username" required placeholder="giaovien@example.com" value={values.email} onChange={change} error={errors.email} disabled={busy} />
        <FormField name="password" label="Mật khẩu" type="password" autoComplete="new-password" required hint="Ít nhất 8 ký tự; tối đa 72 byte." value={values.password} onChange={change} error={errors.password} disabled={busy} />
        <FormField name="school" label="Trường (không bắt buộc)" autoComplete="organization" placeholder="Tên trường của bạn" value={values.school} onChange={change} disabled={busy} />
        <FormField name="province" label="Tỉnh / Thành phố (không bắt buộc)" autoComplete="address-level1" placeholder="Tỉnh hoặc thành phố" value={values.province} onChange={change} disabled={busy} />
        <button type="submit" className="button-primary w-full" disabled={busy}>
          {busy ? <><LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> Đang tạo tài khoản…</> : <>Tạo tài khoản <ArrowRight size={18} aria-hidden="true" /></>}
        </button>
      </form>
      <p className="mt-6 text-center text-sm text-slate-600">Đã có tài khoản? <Link to="/login" className="font-semibold text-brand-700 hover:underline">Đăng nhập</Link></p>
    </AuthLayout>
  )
}
