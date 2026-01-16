export type AuthUser = {
  sub: string
  username: string
  email?: string
  tenantId?: string
  roles: string[]
}

export type AuthTokens = {
  accessToken: string
  refreshToken: string
  expiresAt: number
  refreshExpiresAt: number
}

export type AuthState = {
  user: AuthUser | null
  tokens: AuthTokens | null
  status: "idle" | "loading" | "authenticated" | "unauthenticated"
  error?: string
}

