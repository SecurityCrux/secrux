import { cn } from "@/lib/utils"

type CvssRingProps = {
  score: number
  vector?: string | null
  source?: string | null
  className?: string
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

function scoreColorClass(score: number) {
  if (score >= 9) return "text-red-600"
  if (score >= 7) return "text-orange-600"
  if (score >= 4) return "text-yellow-600"
  if (score > 0) return "text-green-600"
  return "text-muted-foreground"
}

export function CvssRing({ score, vector, source, className }: CvssRingProps) {
  const normalized = clamp(score, 0, 10)
  const progress = normalized / 10
  const size = 120
  const strokeWidth = 10
  const radius = (size - strokeWidth) / 2
  const circumference = 2 * Math.PI * radius
  const dashOffset = circumference * (1 - progress)

  const scoreLabel = Number.isFinite(normalized) ? normalized.toFixed(1) : "—"

  return (
    <div className={cn("flex items-center gap-4", className)}>
      <div className={cn("shrink-0", scoreColorClass(normalized))}>
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="block">
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            className="stroke-current text-muted-foreground/20"
            strokeWidth={strokeWidth}
            fill="none"
          />
          <circle
            cx={size / 2}
            cy={size / 2}
            r={radius}
            className="stroke-current"
            strokeWidth={strokeWidth}
            fill="none"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={dashOffset}
            transform={`rotate(-90 ${size / 2} ${size / 2})`}
          />
          <text
            x="50%"
            y="46%"
            textAnchor="middle"
            dominantBaseline="middle"
            className="fill-current text-2xl font-semibold"
          >
            {scoreLabel}
          </text>
          <text
            x="50%"
            y="64%"
            textAnchor="middle"
            dominantBaseline="middle"
            className="fill-current text-xs text-muted-foreground"
          >
            CVSS
          </text>
        </svg>
      </div>

      <div className="min-w-0 space-y-1 text-sm">
        {source ? (
          <div className="text-xs text-muted-foreground">
            Source: <span className="break-all font-mono">{source}</span>
          </div>
        ) : null}
        {vector ? (
          <div className="text-xs text-muted-foreground">
            Vector: <span className="break-all font-mono">{vector}</span>
          </div>
        ) : null}
      </div>
    </div>
  )
}
