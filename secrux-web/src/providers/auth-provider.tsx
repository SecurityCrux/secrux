import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react"
import { jwtDecode } from "jwt-decode"

import { fetchMe, loginWithPassword, logoutWithToken, refreshWithToken, type PasswordLoginPayload } from "@/services/auth-service"
import { storage, type AuthSnapshot } from "@/lib/storage"
import type { AuthState, AuthTokens, AuthUser } from "@/types/auth"
import type { AuthMeResponse } from "@/types/api"

type AuthContextValue = {
  user: AuthUser | null
  tokens: AuthTokens | null
  status: AuthState["status"]
  error?: string
  login: (payload: PasswordLoginPayload) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

const initialState: AuthState = {
  user: null,
  tokens: null,
  status: "idle",
}

type JwtPayload = {
  sub: string
  preferred_username?: string
  email?: string
  tenant_id?: string
  realm_access?: {
    roles?: string[]
  }
}

function usernameFromEmail(email?: string) {
  if (!email) return undefined
  const atIndex = email.indexOf("@")
  if (atIndex <= 0) return email
  return email.slice(0, atIndex)
}

function decodeUser(token: string): AuthUser {
  const payload = jwtDecode<JwtPayload>(token)
  return {
    sub: payload.sub,
    username: payload.preferred_username ?? usernameFromEmail(payload.email) ?? payload.sub,
    email: payload.email,
    tenantId: payload.tenant_id,
    roles: payload.realm_access?.roles ?? [],
  }
}

function mapMe(me: AuthMeResponse): AuthUser {
  return {
    sub: me.userId,
    username: me.username ?? usernameFromEmail(me.email ?? undefined) ?? me.userId,
    email: me.email ?? undefined,
    tenantId: me.tenantId,
    roles: me.roles ?? [],
  }
}

function toTokens(response: {
  access_token: string
  refresh_token: string
  expires_in: number
  refresh_expires_in: number
}): AuthTokens {
  const now = Date.now()
  return {
    accessToken: response.access_token,
    refreshToken: response.refresh_token,
    expiresAt: now + response.expires_in * 1000,
    refreshExpiresAt: now + response.refresh_expires_in * 1000,
  }
}

function mapSnapshot(snapshot: AuthSnapshot): AuthTokens {
  return {
    accessToken: snapshot.accessToken,
    refreshToken: snapshot.refreshToken,
    expiresAt: snapshot.expiresAt,
    refreshExpiresAt: snapshot.refreshExpiresAt,
  }
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>(initialState)

  const logout = useCallback(() => {
    const refreshToken = storage.readAuth()?.refreshToken
    if (refreshToken) {
      void logoutWithToken(refreshToken)
    }
    storage.clearAuth()
    setState({
      user: null,
      tokens: null,
      status: "unauthenticated",
    })
  }, [])

  const loadUser = useCallback(async (accessToken: string) => {
    try {
      const me = await fetchMe()
      if (me) return mapMe(me)
    } catch (error) {
      console.warn("Failed to fetch /auth/me, falling back to token claims", error)
    }
    return decodeUser(accessToken)
  }, [])

  const hydrate = useCallback(async () => {
    const snapshot = storage.readAuth()
    if (!snapshot) {
      setState((prev) => ({ ...prev, status: "unauthenticated" }))
      return
    }
    const tokens = mapSnapshot(snapshot)
    if (tokens.expiresAt > Date.now()) {
      const user = await loadUser(tokens.accessToken)
      setState({
        user,
        tokens,
        status: "authenticated",
      })
      return
    }
    if (tokens.refreshExpiresAt <= Date.now()) {
      logout()
      return
    }
    try {
      const refreshed = await refreshWithToken(tokens.refreshToken)
      const nextTokens = toTokens(refreshed)
      storage.saveAuth(nextTokens)
      const user = await loadUser(nextTokens.accessToken)
      setState({
        user,
        tokens: nextTokens,
        status: "authenticated",
      })
    } catch (error) {
      console.error("Failed to refresh token", error)
      logout()
    }
  }, [loadUser, logout])

  useEffect(() => {
    void hydrate()
  }, [hydrate])

  const login = useCallback(
    async (payload: PasswordLoginPayload) => {
      setState((prev) => ({ ...prev, status: "loading", error: undefined }))
      try {
        const response = await loginWithPassword(payload)
        const tokens = toTokens(response)
        storage.saveAuth(tokens)
        const user = await loadUser(tokens.accessToken)
        setState({
          user,
          tokens,
          status: "authenticated",
        })
      } catch (error) {
        console.error("login failed", error)
        storage.clearAuth()
        setState({
          user: null,
          tokens: null,
          status: "unauthenticated",
          error: "invalid",
        })
        throw error
      }
    },
    [loadUser]
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      user: state.user,
      tokens: state.tokens,
      status: state.status,
      error: state.error,
      login,
      logout,
    }),
    [login, logout, state.error, state.status, state.tokens, state.user]
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuthContext() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error("useAuthContext must be used within AuthProvider")
  }
  return context
}
