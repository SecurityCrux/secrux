import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

const dateTimeFormatter = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
})

export function persist<T>(key: string, value: T) {
  localStorage.setItem(key, JSON.stringify(value))
}

export function readPersisted<T>(key: string): T | null {
  const raw = localStorage.getItem(key)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as T
  } catch {
    localStorage.removeItem(key)
    return null
  }
}

export function formatDateTime(value?: string | null) {
  if (!value) {
    return "—"
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return dateTimeFormatter.format(date)
}

export function formatPercent(value?: number | null, digits = 1) {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return "—"
  }
  return `${value.toFixed(digits)}%`
}

export function formatGbFromMb(valueMb?: number | null, digits = 2) {
  if (valueMb === null || valueMb === undefined || !Number.isFinite(valueMb) || valueMb < 0) {
    return "—"
  }
  return (valueMb / 1024).toFixed(digits)
}

export function formatBytes(size?: number | null) {
  if (size === null || size === undefined || !Number.isFinite(size) || size <= 0) {
    return "—"
  }
  const units = ["B", "KB", "MB", "GB", "TB"]
  let value = size
  let index = 0
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index += 1
  }
  const precision = index === 0 ? 0 : 1
  return `${value.toFixed(precision)} ${units[index]}`
}
