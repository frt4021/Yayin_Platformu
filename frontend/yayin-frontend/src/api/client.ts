import type { ErrorResponse, TokenResponse } from './types'
import { clearTokens, readTokens, writeTokens } from './tokens'

/**
 * Backend istemcisi.
 *
 * Vite proxy'si /api'yi backend'e yönlendirdiği için adresler görelidir;
 * tarayıcı her şeyi tek origin olarak görür.
 */

/** Oturum düştüğünde AuthProvider'ın haberdar olması için. */
type SessionEndedListener = () => void
let onSessionEnded: SessionEndedListener = () => {}

export function setSessionEndedListener(listener: SessionEndedListener) {
  onSessionEnded = listener
}

export class ApiError extends Error {
  // Alanlar açıkça tanımlı: tsconfig'de erasableSyntaxOnly açık olduğu için
  // constructor parametre özelliği (readonly status: number) kullanılamıyor.
  readonly status: number
  readonly fieldErrors: { field: string; message: string }[]

  constructor(
    message: string,
    status: number,
    fieldErrors: { field: string; message: string }[] = [],
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  try {
    const body = (await response.json()) as ErrorResponse
    if (body.fieldErrors?.length) {
      // Backend alan adını "create.request.username" gibi tam yol olarak
      // veriyor; kullanıcıya yalnızca son parça anlamlı.
      const detail = body.fieldErrors
        .map((f) => `${f.field.split('.').pop()}: ${f.message}`)
        .join(', ')
      return new ApiError(detail, response.status, body.fieldErrors)
    }
    return new ApiError(body.message || `HTTP ${response.status}`, response.status)
  } catch {
    return new ApiError(`HTTP ${response.status}`, response.status)
  }
}

/**
 * Refresh yarışını engelleyen kilit. Aynı anda düşen birden fazla istek
 * tek bir yenileme isteğini paylaşır; aksi halde her biri ayrı ayrı
 * yenilemeye çalışır ve Keycloak, kullanılmış refresh token'ı reddedip
 * oturumu tamamen düşürür.
 */
let refreshInFlight: Promise<boolean> | null = null

function refreshTokens(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight

  refreshInFlight = (async () => {
    const tokens = readTokens()
    if (!tokens) return false
    try {
      const response = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tokens.refreshToken }),
      })
      if (!response.ok) return false
      writeTokens((await response.json()) as TokenResponse)
      return true
    } catch {
      return false
    } finally {
      refreshInFlight = null
    }
  })()

  return refreshInFlight
}

interface RequestOptions {
  method?: string
  body?: unknown
  /** Giriş uçlarında token eklenmez. */
  anonymous?: boolean
}

async function request<T>(path: string, options: RequestOptions = {}, retry = true): Promise<T> {
  const headers: Record<string, string> = {}

  // FormData'da Content-Type ELLE AYARLANMAMALI: tarayıcı multipart sınır
  // dizgisini (boundary) kendisi üretip başlığa ekliyor. Elle yazılırsa
  // boundary eksik kalır ve sunucu gövdeyi çözemez.
  const isForm = options.body instanceof FormData
  if (options.body !== undefined && !isForm) headers['Content-Type'] = 'application/json'

  if (!options.anonymous) {
    const tokens = readTokens()
    if (tokens) headers['Authorization'] = `Bearer ${tokens.accessToken}`
  }

  const response = await fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body:
      options.body === undefined
        ? undefined
        : isForm
          ? (options.body as FormData)
          : JSON.stringify(options.body),
  })

  // Access token varsayılan olarak 5 dakikada doluyor; yenilemeden sayfa
  // kısa sürede kullanılamaz hale gelirdi.
  if (response.status === 401 && retry && !options.anonymous) {
    if (await refreshTokens()) return request<T>(path, options, false)
    clearTokens()
    onSessionEnded()
    throw new ApiError('Oturumunuz sona erdi, lütfen yeniden giriş yapın.', 401)
  }

  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown, opts?: { anonymous?: boolean }) =>
    request<T>(path, { method: 'POST', body, anonymous: opts?.anonymous }),
  /** Dosya yükleme; Content-Type'ı tarayıcı belirler (bkz. request). */
  postForm: <T>(path: string, form: FormData) =>
    request<T>(path, { method: 'POST', body: form }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
