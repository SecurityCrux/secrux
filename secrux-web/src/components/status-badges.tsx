import { useTranslation } from "react-i18next"
import { Badge } from "@/components/ui/badge"
import { cn } from "@/lib/utils"

const STATUS_COLOR_MAP: Record<string, string> = {
  SUCCEEDED: "border-emerald-500/30 text-emerald-600 dark:text-emerald-300",
  RUNNING: "border-sky-500/30 text-sky-600 dark:text-sky-300",
  FAILED: "border-red-500/30 text-red-600 dark:text-red-300",
  PENDING: "border-amber-500/30 text-amber-600 dark:text-amber-300",
  SKIPPED: "border-slate-500/30 text-slate-600 dark:text-slate-300",
  CANCELED: "border-slate-500/30 text-slate-600 dark:text-slate-300",
  CONFIRMED: "border-red-500/30 text-red-600 dark:text-red-300",
  OPEN: "border-red-500/30 text-red-600 dark:text-red-300",
  FALSE_POSITIVE: "border-slate-500/30 text-slate-600 dark:text-slate-300",
  RESOLVED: "border-emerald-500/30 text-emerald-600 dark:text-emerald-300",
  WONT_FIX: "border-amber-500/30 text-amber-600 dark:text-amber-300",
  SYNCED: "border-emerald-500/30 text-emerald-600 dark:text-emerald-300",
}

const SEVERITY_COLOR_MAP: Record<string, string> = {
  CRITICAL: "bg-red-500/15 text-red-600 dark:text-red-300",
  HIGH: "bg-orange-500/15 text-orange-600 dark:text-orange-300",
  MEDIUM: "bg-amber-500/15 text-amber-600 dark:text-amber-300",
  LOW: "bg-sky-500/15 text-sky-600 dark:text-sky-300",
  INFO: "bg-slate-500/15 text-slate-600 dark:text-slate-300",
}

const TASK_TYPE_LABEL_MAP: Record<string, string> = {
  CODE_CHECK: "CODE",
  SECURITY_SCAN: "SCAN",
  SUPPLY_CHAIN: "SUPPLY",
  SCA_CHECK: "SCA",
  IDE_AUDIT: "IDE",
}

const TASK_TYPE_COLOR_MAP: Record<string, string> = {
  CODE_CHECK: "border-indigo-500/30 text-indigo-600 dark:text-indigo-300",
  SECURITY_SCAN: "border-sky-500/30 text-sky-600 dark:text-sky-300",
  SUPPLY_CHAIN: "border-amber-500/30 text-amber-600 dark:text-amber-300",
  SCA_CHECK: "border-fuchsia-500/30 text-fuchsia-600 dark:text-fuchsia-300",
  IDE_AUDIT: "border-emerald-500/30 text-emerald-600 dark:text-emerald-300",
}

export function StatusBadge({ status }: { status?: string | null }) {
  const { t } = useTranslation()
  if (!status) {
    return <Badge variant="outline">—</Badge>
  }
  const label = t(`statuses.${status}`, { defaultValue: status })
  return (
    <Badge variant="outline" className={cn("font-mono text-[10px]", STATUS_COLOR_MAP[status] ?? "text-muted-foreground border-muted")}>
      {label}
    </Badge>
  )
}

export function SeverityBadge({ severity }: { severity?: string | null }) {
  const { t } = useTranslation()
  if (!severity) {
    return <Badge variant="secondary">—</Badge>
  }
  const label = t(`severities.${severity}`, { defaultValue: severity })
  return (
    <Badge variant="secondary" className={cn("font-mono text-[10px]", SEVERITY_COLOR_MAP[severity] ?? "text-muted-foreground")}>
      {label}
    </Badge>
  )
}

export function TaskTypeBadge({ type }: { type?: string | null }) {
  if (!type) {
    return <Badge variant="outline">—</Badge>
  }
  const label = TASK_TYPE_LABEL_MAP[type] ?? type
  return (
    <Badge variant="outline" className={cn("font-mono text-[10px]", TASK_TYPE_COLOR_MAP[type] ?? "text-muted-foreground border-muted")}>
      {label}
    </Badge>
  )
}
