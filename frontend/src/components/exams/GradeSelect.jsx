export default function GradeSelect({ value, onChange, disabled = false, id = 'khoi' }) {
  return (
    <div>
      <label htmlFor={id} className="mb-2 block text-sm font-medium text-slate-700">Khối lớp</label>
      <select id={id} name="khoi" className="input-field" value={value || ''} onChange={(event) => onChange(event.target.value)} disabled={disabled}>
        <option value="">Không phân khối</option>
        {Array.from({ length: 12 }, (_, index) => <option key={index + 1} value={String(index + 1)}>Khối {index + 1}</option>)}
      </select>
    </div>
  )
}
