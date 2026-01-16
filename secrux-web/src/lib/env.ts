const fallback = {
  apiBaseUrl: "http://localhost:8080",
  oidcBaseUrl: "http://localhost:8081",
  oidcRealm: "secrux",
  oidcClientId: "secrux-console",
  oidcScope: "",
  authMode: "keycloak",
  appVersion: "dev",
}

type RuntimeConsoleConfig = {
  apiBaseUrl?: string
  authMode?: string
  oidc?: {
    baseUrl?: string
    realm?: string
    clientId?: string
    scope?: string
  }
  appVersion?: string
}

const runtimeConfig: RuntimeConsoleConfig =
  typeof window !== "undefined"
    ? // eslint-disable-next-line @typescript-eslint/no-explicit-any
      ((window as any).__SECRUX_CONFIG__ as RuntimeConsoleConfig | undefined) ?? {}
    : {}

export const env = {
  apiBaseUrl: runtimeConfig.apiBaseUrl ?? import.meta.env.VITE_API_BASE_URL ?? fallback.apiBaseUrl,
  authMode: runtimeConfig.authMode ?? import.meta.env.VITE_AUTH_MODE ?? fallback.authMode,
  oidc: {
    baseUrl: runtimeConfig.oidc?.baseUrl ?? import.meta.env.VITE_OIDC_BASE_URL ?? fallback.oidcBaseUrl,
    realm: runtimeConfig.oidc?.realm ?? import.meta.env.VITE_OIDC_REALM ?? fallback.oidcRealm,
    clientId: runtimeConfig.oidc?.clientId ?? import.meta.env.VITE_OIDC_CLIENT_ID ?? fallback.oidcClientId,
    scope: runtimeConfig.oidc?.scope ?? import.meta.env.VITE_OIDC_SCOPE ?? fallback.oidcScope,
  },
  appVersion: runtimeConfig.appVersion ?? import.meta.env.VITE_APP_VERSION ?? fallback.appVersion,
} as const

export const oidcUrls = {
  token: `${env.oidc.baseUrl}/realms/${env.oidc.realm}/protocol/openid-connect/token`,
  userInfo: `${env.oidc.baseUrl}/realms/${env.oidc.realm}/protocol/openid-connect/userinfo`,
  logout: `${env.oidc.baseUrl}/realms/${env.oidc.realm}/protocol/openid-connect/logout`,
}
