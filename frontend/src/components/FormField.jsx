import { useState } from 'react'
import { Eye, EyeOff } from 'lucide-react'

export default function FormField({ name, label, type = 'text', error, hint, ...props }) {
  const [visible, setVisible] = useState(false)
  const isPassword = type === 'password'
  return (
    <div>
      <label htmlFor={name} className="mb-2 block text-sm font-medium text-slate-700">{label}</label>
      <div className="relative">
        <input id={name} name={name} type={isPassword && visible ? 'text' : type}
          className={`input-field ${isPassword ? 'pr-12' : ''} ${error ? 'border-rose-400' : ''}`}
          aria-invalid={Boolean(error)} aria-describedby={error ? `${name}-error` : hint ? `${name}-hint` : undefined} {...props} />
        {isPassword && (
          <button type="button" onClick={() => setVisible(!visible)} aria-label={visible ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
            aria-pressed={visible} className="absolute right-3 top-1/2 -translate-y-1/2 rounded p-1 text-slate-600 hover:text-brand-700">
            {visible ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
          </button>
        )}
      </div>
      {error && <p id={`${name}-error`} className="mt-2 text-xs text-rose-700">{error}</p>}
      {!error && hint && <p id={`${name}-hint`} className="mt-2 text-xs text-slate-600">{hint}</p>}
    </div>
  )
}
