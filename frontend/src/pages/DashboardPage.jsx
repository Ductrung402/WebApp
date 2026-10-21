import { useEffect, useRef, useState } from 'react'
import { GraduationCap, KeyRound, LoaderCircle, Mail, MapPin, Pencil, Save, School, UserRound } from 'lucide-react'
import FormField from '../components/FormField.jsx'
import FormMessage from '../components/FormMessage.jsx'
import ChangePasswordDialog from '../components/ChangePasswordDialog.jsx'
import { useAuth } from '../contexts/AuthContext.jsx'
import { updateProfile } from '../services/authService.js'
import { getAuthError } from '../utils/authValidation.js'
import { getAccessToken } from '../utils/tokenStorage.js'

export default function DashboardPage() {
  const { teacher, updateTeacher } = useAuth()
  const [editing, setEditing] = useState(false)
  const [values, setValues] = useState({ fullName: '', school: '', province: '' })
  const [errors, setErrors] = useState({})
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [saving, setSaving] = useState(false)
  const [passwordDialogOpen, setPasswordDialogOpen] = useState(false)
  const alive = useRef(true)
  const submitLock = useRef(false)
  const request = useRef(null)

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false; request.current?.abort() }
  }, [])

  function beginEdit() {
    setValues({ fullName: teacher.fullName, school: teacher.school || '', province: teacher.province || '' })
    setErrors({})
    setError('')
    setNotice('')
    setEditing(true)
  }

  function change(event) {
    const { name, value } = event.target
    setValues((current) => ({ ...current, [name]: value }))
    setErrors((current) => ({ ...current, [name]: '' }))
    setError('')
  }

  async function save(event) {
    event.preventDefault()
    if (submitLock.current) return
    const validation = values.fullName.trim() ? {} : { fullName: 'Vui lòng nhập họ và tên.' }
    setErrors(validation)
    setError('')
    if (Object.keys(validation).length) return
    submitLock.current = true
    setSaving(true)
    const token = getAccessToken()
    request.current = new AbortController()
    try {
      const updated = await updateProfile(values, request.current.signal)
      if (!alive.current || token !== getAccessToken()) return
      if (updated.idGv !== teacher.idGv || typeof updated.fullName !== 'string' || !updated.fullName.trim()) throw new Error('Phản hồi cập nhật hồ sơ không hợp lệ.')
      updateTeacher(updated)
      setEditing(false)
      setNotice('Đã cập nhật thông tin cá nhân.')
    } catch (error) {
      if (alive.current && token === getAccessToken()) setError(getAuthError(error))
    } finally {
      submitLock.current = false
      if (alive.current) setSaving(false)
    }
  }
  const fields = [
    { label: 'Họ và tên', value: teacher.fullName, icon: UserRound },
    { label: 'Email', value: teacher.email, icon: Mail },
    { label: 'Trường', value: teacher.school || 'Chưa cung cấp', icon: School },
    { label: 'Tỉnh / Thành phố', value: teacher.province || 'Chưa cung cấp', icon: MapPin },
  ]
  return (
    <>
      <p className="text-xs font-semibold uppercase tracking-[0.16em] text-brand-700">Thông tin cá nhân</p>
      <h1 className="mt-3 text-3xl font-bold tracking-tight text-slate-900">Hồ sơ giáo viên</h1>
      <section className="mt-8 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm" aria-labelledby="profile-title">
        <div className="flex flex-wrap items-center gap-4 border-b border-slate-100 px-6 py-6 sm:px-8">
          <span className="rounded-xl bg-brand-50 p-3 text-brand-700"><GraduationCap size={24} aria-hidden="true" /></span>
          <h2 id="profile-title" className="flex-1 text-lg font-semibold">Thông tin tài khoản</h2>
          <div className="flex flex-wrap items-center gap-3">
            <button type="button" disabled={saving} onClick={() => { setNotice(''); setPasswordDialogOpen(true) }} className="button-secondary min-h-11 !px-3 !text-xs sm:!px-4 sm:!text-sm"><KeyRound size={16} aria-hidden="true" />Đổi mật khẩu</button>
            {!editing && <button type="button" onClick={beginEdit} className="button-secondary min-h-11 !px-3 !text-xs sm:!px-4 sm:!text-sm"><Pencil size={16} aria-hidden="true" />Chỉnh sửa thông tin</button>}
          </div>
        </div>
        {notice && <div className="px-6 pt-6 sm:px-8"><FormMessage success>{notice}</FormMessage></div>}
        {editing ? (
          <form onSubmit={save} noValidate aria-busy={saving} className="space-y-5 p-6 sm:p-8">
            <FormMessage>{error}</FormMessage>
            <fieldset disabled={saving} className="grid min-w-0 gap-5 sm:grid-cols-2">
              <FormField name="fullName" label="Họ và tên" autoComplete="name" required value={values.fullName} onChange={change} error={errors.fullName} />
              <FormField name="school" label="Trường công tác" autoComplete="organization" value={values.school} onChange={change} />
              <FormField name="province" label="Tỉnh / Thành phố" autoComplete="address-level1" value={values.province} onChange={change} />
            </fieldset>
            <div className="flex flex-wrap justify-end gap-3 border-t border-slate-100 pt-5">
              <button type="button" disabled={saving} onClick={() => { setEditing(false); setError(''); setErrors({}) }} className="button-secondary">Hủy</button>
              <button type="submit" disabled={saving} className="button-primary">{saving ? <LoaderCircle size={16} className="animate-spin" aria-hidden="true" /> : <Save size={16} aria-hidden="true" />}{saving ? 'Đang lưu…' : 'Lưu thay đổi'}</button>
            </div>
          </form>
        ) : <>
          <dl className="grid gap-7 p-6 sm:grid-cols-2 sm:p-8">
            {fields.map(({ label, value, icon: Icon }) => (
              <div key={label} className="min-w-0">
                <dt className="flex items-center gap-2 text-xs font-medium text-slate-600"><Icon size={15} aria-hidden="true" /> {label}</dt>
                <dd className="mt-3 break-words text-sm font-medium text-slate-800">{value}</dd>
              </div>
            ))}
          </dl>
        </>}
      </section>
      {passwordDialogOpen && <ChangePasswordDialog onClose={() => setPasswordDialogOpen(false)} onSuccess={() => { setPasswordDialogOpen(false); setNotice('Đã đổi mật khẩu thành công.') }} />}
    </>
  )
}
