import { LoaderCircle, Plus, Save } from 'lucide-react'
import FormField from '../FormField.jsx'
import FormMessage from '../FormMessage.jsx'
import GradeSelect from './GradeSelect.jsx'
import QuestionEditor from './QuestionEditor.jsx'

export default function ExamEditor({ draft, onChange, onSave, onCancel, saving, error, errors = {}, isNew = false }) {
  function updateQuestion(index, field, value) {
    onChange({
      ...draft,
      cauHoiList: draft.cauHoiList.map((question, position) => position === index ? { ...question, [field]: value } : question),
    })
  }

  function removeQuestion(index) {
    onChange({ ...draft, cauHoiList: draft.cauHoiList.filter((_, position) => position !== index) })
  }

  function addQuestion() {
    onChange({ ...draft, cauHoiList: [...draft.cauHoiList, { noiDung: '', dapAnA: '', dapAnB: '', dapAnC: '', dapAnD: '', dapAnDung: 'A' }] })
  }

  return (
    <form onSubmit={(event) => { event.preventDefault(); onSave() }} noValidate aria-busy={saving} className="space-y-5">
      <FormMessage>{error}</FormMessage>
      <div className="grid gap-5 rounded-2xl border border-slate-200 bg-white p-5 sm:grid-cols-[1fr_180px] sm:p-6">
        <FormField name="tenDeThi" label="Tên đề thi" value={draft.tenDeThi} onChange={(event) => onChange({ ...draft, tenDeThi: event.target.value })} error={errors.tenDeThi} disabled={saving} required />
        <GradeSelect value={draft.khoi} onChange={(value) => onChange({ ...draft, khoi: value })} disabled={saving} />
      </div>
      <div className="flex items-center justify-between gap-3"><h2 className="text-lg font-semibold">Danh sách câu hỏi</h2><span className="rounded-full bg-brand-100 px-3 py-1 text-xs font-semibold text-brand-700">{draft.cauHoiList.length} câu hỏi</span></div>
      {errors.cauHoiList && <FormMessage>{errors.cauHoiList}</FormMessage>}
      {draft.cauHoiList.map((question, index) => (
        <QuestionEditor key={index} question={question} index={index} onChange={(field, value) => updateQuestion(index, field, value)}
          onRemove={() => removeQuestion(index)} canRemove={draft.cauHoiList.length > 1} disabled={saving} errors={errors.questions?.[index]} />
      ))}
      <button type="button" className="button-secondary w-full" onClick={addQuestion} disabled={saving}><Plus size={17} aria-hidden="true" /> Thêm câu hỏi</button>
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
        <button type="button" onClick={onCancel} className="button-secondary" disabled={saving}>{isNew ? 'Quay lại tài liệu' : 'Hủy chỉnh sửa'}</button>
        <button type="submit" className="button-primary" disabled={saving}>
          {saving ? <LoaderCircle size={17} className="animate-spin" aria-hidden="true" /> : <Save size={17} aria-hidden="true" />}
          {saving ? 'Đang lưu…' : isNew ? 'Lưu vào kho đề' : 'Lưu thay đổi'}
        </button>
      </div>
    </form>
  )
}
