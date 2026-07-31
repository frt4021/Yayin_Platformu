import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'
import type { Role } from '@/api/types'

/**
 * Rota koruması.
 *
 * Bu yalnızca gezinme kolaylığı — yetkisiz kullanıcıya kapıyı göstermemek
 * için. Gerçek koruma backend'deki {@code @RolesAllowed}; buradaki kontrol
 * atlatılsa bile veri gelmez.
 */
export function RequireAuth({ roles }: { roles?: Role[] }) {
  const { session, hasRole } = useAuth()
  const location = useLocation()

  if (!session) {
    // Giriş sonrası kullanıcıyı gitmek istediği sayfaya döndürebilmek için
    // hedefi taşıyoruz.
    return <Navigate to="/giris" replace state={{ from: location }} />
  }

  if (roles && !hasRole(...roles)) {
    return <Navigate to="/yetkisiz" replace />
  }

  return <Outlet />
}
