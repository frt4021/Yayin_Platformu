import type { Role, TokenResponse } from './types'
import { ROLES } from './types'

/**
 * Token saklama ve okuma.
 *
 * sessionStorage tercih edildi: localStorage sekme kapansa da kalır ve
 * paylaşılan bir makinede bir sonraki kullanıcıya açık oturum devreder.
 * Her ikisi de XSS'e karşı korumasızdır; asıl çözüm httpOnly çerezdir,
 * ancak bu backend bearer token bekliyor.
 */

const KEY = 'yayin.tokens'

export interface StoredTokens {
  accessToken: string
  refreshToken: string
}

export function readTokens(): StoredTokens | null {
  const raw = sessionStorage.getItem(KEY)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as StoredTokens
    return parsed.accessToken && parsed.refreshToken ? parsed : null
  } catch {
    return null
  }
}

export function writeTokens(response: TokenResponse): StoredTokens {
  const tokens: StoredTokens = {
    accessToken: response.access_token,
    refreshToken: response.refresh_token,
  }
  sessionStorage.setItem(KEY, JSON.stringify(tokens))
  return tokens
}

export function clearTokens() {
  sessionStorage.removeItem(KEY)
}

interface JwtClaims {
  sub?: string
  preferred_username?: string
  email?: string
  exp?: number
  realm_access?: { roles?: string[] }
  resource_access?: Record<string, { roles?: string[] }>
}

/**
 * Token'ın payload'ını okur.
 *
 * İmza DOĞRULANMAZ ve doğrulanmasına gerek yok: buradan çıkan bilgi yalnızca
 * arayüzü şekillendirmek için (hangi menü görünsün) kullanılır. Gerçek yetki
 * kararını her istekte backend veriyor; kullanıcı token'ı kurcalasa bile
 * sunucu reddeder.
 */
export function decodeClaims(accessToken: string): JwtClaims {
  try {
    const payload = accessToken.split('.')[1]
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    // Türkçe karakterlerin bozulmaması için UTF-8 olarak çözülüyor.
    const text = decodeURIComponent(
      Array.from(json, (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
    )
    return JSON.parse(text) as JwtClaims
  } catch {
    return {}
  }
}

/**
 * Token'daki uygulama rolü.
 *
 * Roller Keycloak'ta client rolü olarak tanımlı (resource_access altında),
 * ama realm rolü olarak da tanımlanabilirler; ikisine birden bakılıyor ki
 * Keycloak tarafındaki bir yapılandırma değişikliği arayüzü bozmasın.
 */
export function roleOf(accessToken: string): Role | null {
  const claims = decodeClaims(accessToken)
  const all = [
    ...(claims.realm_access?.roles ?? []),
    ...Object.values(claims.resource_access ?? {}).flatMap((entry) => entry.roles ?? []),
  ]
  return ROLES.find((role) => all.includes(role)) ?? null
}

export function usernameOf(accessToken: string): string {
  return decodeClaims(accessToken).preferred_username ?? ''
}
