export function Logo({ className = "flex items-center text-xl font-semibold tracking-tight" }: { className?: string }) {
  const logoSrc = `${import.meta.env.BASE_URL}logo-m.svg`

  return (
    <div className={className}>
      <img src={logoSrc} alt="Secrux" className="h-7 w-7" />
      <span className="ml-2">Secrux</span>
    </div>
  )
}
