import type { AuthTokens } from "@/types/auth"

const STORAGE_KEYS = {
  auth: "secrux.auth.tokens",
  theme: "secrux.theme",
  uiSetup: "secrux.ui.setup.v1",
} as const

export type AuthSnapshot = AuthTokens

function authStorage() {
  return window.sessionStorage
}

export const storage = {
  saveAuth(data: AuthSnapshot) {
    try {
      authStorage().setItem(STORAGE_KEYS.auth, JSON.stringify(data))
    } catch {
      // ignore
    }
  },
  readAuth(): AuthSnapshot | null {
    try {
      const store = authStorage()
      const raw = store.getItem(STORAGE_KEYS.auth)
      return raw ? (JSON.parse(raw) as AuthSnapshot) : null
    } catch {
      try {
        authStorage().removeItem(STORAGE_KEYS.auth)
      } catch {
        // ignore
      }
      return null
    }
  },
  clearAuth() {
    try {
      authStorage().removeItem(STORAGE_KEYS.auth)
    } catch {
      // ignore
    }
  },
  setTheme(theme: string) {
    try {
      localStorage.setItem(STORAGE_KEYS.theme, theme)
    } catch {
      // ignore
    }
  },
  getTheme(): string | null {
    try {
      return localStorage.getItem(STORAGE_KEYS.theme)
    } catch {
      return null
    }
  },
  isUiSetupDone(): boolean {
    try {
      return localStorage.getItem(STORAGE_KEYS.uiSetup) === "1"
    } catch {
      return false
    }
  },
  markUiSetupDone() {
    try {
      localStorage.setItem(STORAGE_KEYS.uiSetup, "1")
    } catch {
      // ignore
    }
  },
}
