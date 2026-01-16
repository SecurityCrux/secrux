import * as React from "react"
import { Check, Minus } from "lucide-react"

import { cn } from "@/lib/utils"

export type CheckboxSize = "sm" | "md"

export type CheckboxProps = Omit<React.InputHTMLAttributes<HTMLInputElement>, "type" | "size"> & {
  indeterminate?: boolean
  size?: CheckboxSize
}

export const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  ({ className, indeterminate, size = "sm", disabled, ...props }, forwardedRef) => {
    const inputRef = React.useRef<HTMLInputElement>(null)

    React.useImperativeHandle(forwardedRef, () => inputRef.current as HTMLInputElement)

    React.useEffect(() => {
      if (!inputRef.current) return
      inputRef.current.indeterminate = Boolean(indeterminate)
    }, [indeterminate])

    const boxSize = size === "md" ? "h-5 w-5" : "h-4 w-4"
    const iconSize = size === "md" ? "h-4 w-4" : "h-3.5 w-3.5"

    return (
      <span className={cn("relative inline-flex shrink-0 align-middle", boxSize, className)}>
        <input
          ref={inputRef}
          type="checkbox"
          disabled={disabled}
          className="peer absolute inset-0 m-0 h-full w-full cursor-pointer opacity-0 disabled:cursor-not-allowed"
          {...props}
        />
        <span
          className={cn(
            "h-full w-full rounded-md border border-input bg-background shadow-sm transition-colors",
            "peer-focus-visible:outline-none peer-focus-visible:ring-2 peer-focus-visible:ring-ring peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-background",
            indeterminate ? "border-primary bg-primary" : "peer-checked:border-primary peer-checked:bg-primary",
            "peer-disabled:bg-muted peer-disabled:shadow-none peer-disabled:border-muted-foreground/20",
            "peer-disabled:peer-checked:bg-muted",
            indeterminate && disabled ? "bg-muted" : null
          )}
        />
        {indeterminate ? (
          <Minus
            aria-hidden="true"
            className={cn(
              "pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 text-primary-foreground",
              iconSize,
              disabled ? "text-muted-foreground" : null
            )}
          />
        ) : (
          <Check
            aria-hidden="true"
            className={cn(
              "pointer-events-none absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 text-primary-foreground opacity-0 transition-opacity peer-checked:opacity-100",
              iconSize,
              disabled ? "text-muted-foreground" : null
            )}
          />
        )}
      </span>
    )
  }
)
Checkbox.displayName = "Checkbox"

