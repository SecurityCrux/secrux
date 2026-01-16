import { cn } from "@/lib/utils"

type SpinnerProps = {
  className?: string
  size?: number
}

export function Spinner({ className, size = 20 }: SpinnerProps) {
  return (
    <svg
      className={cn("animate-spin text-primary", className)}
      height={size}
      width={size}
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      role="presentation"
    >
      <circle
        className="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        strokeWidth="4"
      />
      <path
        className="opacity-75"
        d="M4 12a8 8 0 018-8"
        stroke="currentColor"
        strokeWidth="4"
        strokeLinecap="round"
      />
    </svg>
  )
}

