import { useState } from 'react'
import { ArrowRight, LoaderCircle } from 'lucide-react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import FormField from '../components/FormField.jsx'
import FormMessage from '../components/FormMessage.jsx'
import { useAuth } from '../contexts/AuthContext.jsx'
import AuthLayout from '../layouts/AuthLayout.jsx'
import { getAuthError, validateAuthForm } from '../utils/authValidation.js'

export default function LoginPage() {
  const { login, sessionNotice } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()
  const [values, setValues] = useState({ email: location.state?.registeredEmail || '', password: '' })
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
    const validation = validateAuthForm(values)
    setErrors(validation)
    setError('')
    if (Object.keys(validation).length) return
    setBusy(true)
    try {
      await login(values)
      navigate('/dashboard', { replace: true })
    } catch (error) {
      setError(getAuthError(error))
    } finally { setBusy(false) }
  }

  return (
    <AuthLayout>
      <p className="mb-3 text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Chào mừng bạn đến với EZam</p>
      <h1 className="text-3xl font-bold tracking-tight text-slate-900">Đăng nhập</h1>
      <p className="mt-3 text-sm leading-6 text-slate-600">Đăng nhập tài khoản EZam để tiếp tục hành trình dạy học của bạn.</p>
      <form onSubmit={submit} noValidate className="mt-8 space-y-5" aria-busy={busy}>
        {location.state?.registrationSuccess && <FormMessage success>Tài khoản đã được tạo. Hãy đăng nhập để tiếp tục.</FormMessage>}
        {sessionNotice && <FormMessage>{sessionNotice}</FormMessage>}
        <FormMessage>{error}</FormMessage>
        <FormField name="email" label="Email" type="email" autoComplete="username"  value={values.email} onChange={change} error={errors.email} disabled={busy} />
        <FormField name="password" label="Mật khẩu" type="password" autoComplete="current-password" required value={values.password} onChange={change} error={errors.password} disabled={busy} />
        <button type="submit" className="button-primary w-full" disabled={busy}>
          {busy ? <><LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> Đang đăng nhập…</> : <>Đăng nhập <ArrowRight size={18} aria-hidden="true" /></>}
        </button>
      </form>
      <p className="mt-7 text-center text-sm text-slate-600">Chưa có tài khoản? <Link to="/register" className="font-semibold text-brand-700 hover:underline">Tạo tài khoản</Link></p>
    </AuthLayout>
  )
}
