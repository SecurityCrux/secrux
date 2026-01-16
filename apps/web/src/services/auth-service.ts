import axios from "axios"
import { apiClient } from "@/lib/api-client"
import { env, oidcUrls } from "@/lib/env"
import type { ApiResponse, AuthMeResponse } from "@/types/api"

type TokenResponse = {
  access_token: string
  expires_in: number
  refresh_expires_in: number
  refresh_token: string
  token_type: string
  scope: string
}

export type PasswordLoginPayload = {
  username: string
  password: string
}

export async function loginWithPassword(payload: PasswordLoginPayload) {
  if (env.authMode.toLowerCase() === "local") {
    const { data } = await apiClient.post<TokenResponse>("/auth/login", payload)
    return data
  }
  const body = new URLSearchParams({
    grant_type: "password",
    client_id: env.oidc.clientId,
    username: payload.username,
    password: payload.password,
  })
  const scope = env.oidc.scope.trim()
  if (scope) {
    body.set("scope", scope)
  }

  const { data } = await axios.post<TokenResponse>(oidcUrls.token, body, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
  })

  return data
}

export async function refreshWithToken(refreshToken: string) {
  if (env.authMode.toLowerCase() === "local") {
    const { data } = await apiClient.post<TokenResponse>("/auth/refresh", { refreshToken })
    return data
  }
  const body = new URLSearchParams({
    grant_type: "refresh_token",
    client_id: env.oidc.clientId,
    refresh_token: refreshToken,
  })

  const { data } = await axios.post<TokenResponse>(oidcUrls.token, body, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
  })

  return data
}

export async function logoutWithToken(refreshToken: string) {
  const token = refreshToken.trim()
  if (!token) return
  if (env.authMode.toLowerCase() === "local") {
    await apiClient.post("/auth/logout", { refreshToken: token })
    return
  }
  const body = new URLSearchParams({
    client_id: env.oidc.clientId,
    refresh_token: token,
  })
  await axios.post(oidcUrls.logout, body, {
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
  })
}

export async function fetchMe() {
  const { data } = await apiClient.get<ApiResponse<AuthMeResponse>>("/auth/me")
  return data.data
}
