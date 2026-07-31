import { createContext, use, useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { setSessionEndedListener } from '@/api/client'
import { authApi } from '@/api/endpoints'
import { clearTokens, readTokens, roleOf, usernameOf, writeTokens } from '@/api/tokens'
import type { Role } from '@/api/types'

interface Session {
  username: string
  role: Role | null
}

interface AuthValue {
  session: Session | null
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  /** Verilen rollerden birine sahip mi. Yalnızca arayüzü şekillendirir. */
  hasRole: (...roles: Role[]) => boolean
}

const AuthContext = createContext<AuthValue | null>(null)

function sessionFrom(accessToken: string): Session {
  return { username: usernameOf(accessToken), role: roleOf(accessToken) }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => {
    const tokens = readTokens()
    return tokens ? sessionFrom(tokens.accessToken) : null
  })

  // Token yenilenemediğinde api katmanı bunu tetikler; oturumu burada
  // düşürüyoruz ki yönlendirme tek yerden yönetilsin.
  useEffect(() => {
    setSessionEndedListener(() => setSession(null))
    return () => setSessionEndedListener(() => {})
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const tokens = writeTokens(await authApi.login(username, password))
    setSession(sessionFrom(tokens.accessToken))
  }, [])

  const logout = useCallback(async () => {
    const tokens = readTokens()
    clearTokens()
    setSession(null)
    if (tokens) {
      // Keycloak oturumunu da kapat. Başarısız olsa da yerel durum temizlendi,
      // bu yüzden hatayı yutuyoruz — kullanıcı zaten çıkmış durumda.
      await authApi.logout(tokens.refreshToken).catch(() => {})
    }
  }, [])

  const value = useMemo<AuthValue>(
    () => ({
      session,
      login,
      logout,
      hasRole: (...roles: Role[]) => (session?.role ? roles.includes(session.role) : false),
    }),
    [session, login, logout],
  )

  return <AuthContext value={value}>{children}</AuthContext>
}

export function useAuth(): AuthValue {
  const value = use(AuthContext)
  if (!value) throw new Error('useAuth, AuthProvider içinde kullanılmalı.')
  return value
}
