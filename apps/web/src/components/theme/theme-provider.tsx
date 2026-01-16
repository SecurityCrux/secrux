import { createContext, useContext, useEffect, useMemo, useState } from "react"
import { storage } from "@/lib/storage"

export type Theme = "light" | "dark" | "system"

type ThemeContextValue = {
  theme: Theme
  setTheme: (theme: Theme) => void
}

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined)

function applyTheme(theme: Theme) {
  const root = window.document.documentElement
  const resolvedTheme =
    theme === "system"
      ? window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light"
      : theme
  root.classList.remove("light", "dark")
  root.classList.add(resolvedTheme)
}

export function ThemeProvider({
  children,
  defaultTheme = "system",
}: {
  children: React.ReactNode
  defaultTheme?: Theme
}) {
  const [theme, setThemeState] = useState<Theme>(defaultTheme)

  useEffect(() => {
    const stored = storage.getTheme()
    if (stored === "light" || stored === "dark" || stored === "system") {
      setThemeState(stored)
      applyTheme(stored)
    } else {
      applyTheme(defaultTheme)
    }
  }, [defaultTheme])

  useEffect(() => {
    const handler = (event: MediaQueryListEvent) => {
      if (theme === "system") {
        applyTheme(event.matches ? "dark" : "light")
      }
    }
    const media = window.matchMedia("(prefers-color-scheme: dark)")
    media.addEventListener("change", handler)
    return () => media.removeEventListener("change", handler)
  }, [theme])

  const setTheme = (next: Theme) => {
    storage.setTheme(next)
    setThemeState(next)
    applyTheme(next)
  }

  const value = useMemo(() => ({ theme, setTheme }), [theme])

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const ctx = useContext(ThemeContext)
  if (!ctx) {
    throw new Error("useTheme must be used inside ThemeProvider")
  }
  return ctx
}

