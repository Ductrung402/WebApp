export default function Brand() {
  return (
    <span className="inline-flex items-center gap-3">
      <img
        src="/logo.svg"
        alt="Logo EZam"
        width="40"
        height="40"
        className="h-10 w-10 shrink-0 drop-shadow-sm transition-transform hover:scale-105"
      />
      <span className="text-xl font-bold tracking-tight text-slate-900">EZ<span className="text-[#1b5232]">am</span></span>
    </span>
  )
}
