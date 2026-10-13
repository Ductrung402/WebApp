import { FileText, LoaderCircle, Sparkles, Upload } from 'lucide-react'
import FormField from '../FormField.jsx'
import FormMessage from '../FormMessage.jsx'
import GradeSelect from './GradeSelect.jsx'

export default function AiSourceForm({ values, onChange, onFileChange, fileName, fileError, errors, error, busy, reading, onSubmit }) {
  return (
    <form onSubmit={(event) => { event.preventDefault(); onSubmit() }} noValidate aria-busy={busy} className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_240px]">
      <div className="space-y-5 rounded-2xl border border-slate-200 bg-white p-5 sm:p-7">
        <FormMessage>{error}</FormMessage>
        <FormField name="tenDeThi" label="Tên đề thi" placeholder="Ví dụ: Ôn tập kiến thức về nước" value={values.tenDeThi} onChange={(event) => onChange('tenDeThi', event.target.value)} error={errors.tenDeThi} disabled={busy} required />
        <div className="grid gap-5 sm:grid-cols-2">
          <GradeSelect value={values.khoi} onChange={(value) => onChange('khoi', value)} disabled={busy} />
          <FormField name="numQuestions" label="Số lượng câu hỏi" type="number" min="1" max="50" step="1" hint="Từ 1 đến 50 câu hỏi." value={values.numQuestions} onChange={(event) => onChange('numQuestions', event.target.value)} error={errors.numQuestions} disabled={busy} required />
        </div>
        <div>
          <label htmlFor="rawText" className="mb-2 block text-sm font-medium text-slate-700">Văn bản tài liệu</label>
          <textarea id="rawText" name="rawText" rows={10} className="input-field resize-y leading-7" placeholder="Dán nội dung bài học, ghi chú hoặc tài liệu cần tạo câu hỏi…" value={values.rawText} onChange={(event) => onChange('rawText', event.target.value)} disabled={busy || reading} aria-invalid={Boolean(errors.rawText)} aria-describedby={errors.rawText ? 'rawText-error' : 'source-hint'} />
          {errors.rawText && <p id="rawText-error" className="mt-2 text-xs text-rose-700">{errors.rawText}</p>}
          <p id="source-hint" className="mt-2 text-xs leading-5 text-slate-500">Câu hỏi được tạo từ nội dung này. Bạn có thể kiểm duyệt và chỉnh sửa trước khi lưu.</p>
        </div>
        <div className="rounded-xl border border-dashed border-slate-300 bg-slate-50 p-4 focus-within:ring-2 focus-within:ring-sky-200">
          <label htmlFor="sourceFile" className="flex cursor-pointer items-center gap-3">
            <Upload size={20} className="shrink-0 text-brand-700" aria-hidden="true" />
            <span className="min-w-0"><span className="block text-sm font-semibold text-slate-700">{reading ? 'Đang đọc tệp…' : 'Chọn tệp văn bản .txt'}</span><span className="mt-1 block break-words text-xs text-slate-500">{fileName || 'UTF-8 · tối đa 1 MB'}</span></span>
          </label>
          <input id="sourceFile" type="file" accept=".txt,text/plain" aria-label="Tệp văn bản (.txt)" onChange={onFileChange} disabled={busy || reading} className="sr-only" />
        </div>
        {fileError && <FormMessage>{fileError}</FormMessage>}
        <button type="submit" className="button-primary w-full sm:w-auto" disabled={busy || reading}>
          {busy ? <LoaderCircle size={18} className="animate-spin" aria-hidden="true" /> : <Sparkles size={18} aria-hidden="true" />}
          {busy ? 'AI đang sinh câu hỏi…' : 'Sinh đề bằng AI'}
        </button>
        {busy && <div role="status" className="flex items-center gap-3 rounded-xl border border-sky-200 bg-sky-50 p-4 text-sm leading-6 text-brand-700"><LoaderCircle size={20} className="shrink-0 animate-spin" aria-hidden="true" /><span>AI đang đọc tài liệu và soạn câu hỏi. Vui lòng chờ…</span></div>}
      </div>
      <aside className="h-fit rounded-2xl border border-sky-100 bg-sky-50 p-6">
        <FileText size={25} className="text-brand-700" aria-hidden="true" /><h2 className="mt-4 font-semibold text-slate-900">Từ tài liệu đến đề thi</h2>
        <ol className="mt-5 space-y-4 text-sm leading-6 text-slate-600"><li><strong className="font-medium text-slate-800">1. Nhập tài liệu</strong><br />Nguồn rõ ràng giúp câu hỏi sát nội dung.</li><li><strong className="font-medium text-slate-800">2. Kiểm duyệt câu hỏi</strong><br />Sửa nội dung, phương án và đáp án đúng.</li><li><strong className="font-medium text-slate-800">3. Lưu vào kho đề</strong><br />Chỉ lưu khi bạn đã kiểm tra xong.</li></ol>
      </aside>
    </form>
  )
}
