import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  AudioLinesIcon,
  ClapperboardIcon,
  FilmIcon,
  HistoryIcon,
  ImageIcon,
  LogOutIcon,
  MonitorPlayIcon,
  RadioIcon,
  UserIcon,
  UsersIcon,
} from 'lucide-react'
import type { Role } from '@/api/types'
import { PlayerProvider, usePlayers } from '@/player/PlayerContext'
import { PersistentPlayers, WATCH_PATH } from '@/player/PersistentPlayers'
import { PersistentRadio } from '@/player/PersistentRadio'

interface NavItem {
  to: string
  label: string
  icon: typeof UsersIcon
  /** Tanımlıysa yalnızca bu rollere gösterilir. */
  roles?: Role[]
}

const NAV: NavItem[] = [
  { to: WATCH_PATH, label: 'İzle', icon: MonitorPlayIcon },
  { to: '/kanallar', label: 'Kanallar', icon: RadioIcon },
  { to: '/radyolar', label: 'Radyolar', icon: AudioLinesIcon },
  { to: '/geriye-sarma', label: 'Geriye sarma', icon: HistoryIcon },
  { to: '/klipler', label: 'Klipler', icon: ClapperboardIcon },
  { to: '/videolar', label: 'Videolar', icon: FilmIcon },
  { to: '/galeri', label: 'Galeri', icon: ImageIcon },
  { to: '/profil', label: 'Profilim', icon: UserIcon },
  { to: '/yonetim/kullanicilar', label: 'Kullanıcılar', icon: UsersIcon, roles: ['Yönetici'] },
]

export function AppLayout() {
  const { session, logout, hasRole } = useAuth()
  const navigate = useNavigate()

  async function onLogout() {
    await logout()
    navigate('/giris', { replace: true })
  }

  return (
    // PlayerProvider, PersistentPlayers ve PersistentRadio bilerek
    // <Outlet/>'in DIŞINDA: sayfa değiştiğinde unmount olmasınlar, yayın ve
    // ses kesilmesin.
    <PlayerProvider>
      <Shell session={session} hasRole={hasRole} onLogout={onLogout} />
    </PlayerProvider>
  )
}

/**
 * Sayfa iskeleti. {@link AppLayout}'tan ayrı bir bileşen çünkü oynatıcı
 * durumunu okuması gerekiyor ve {@code PlayerProvider}'ı render eden bileşen
 * kendi sağladığı context'i kullanamaz.
 */
function Shell({
  session,
  hasRole,
  onLogout,
}: {
  session: ReturnType<typeof useAuth>['session']
  hasRole: ReturnType<typeof useAuth>['hasRole']
  onLogout: () => Promise<void>
}) {
  const { radioId, radioPaused, toggleRadioPause, stopRadio } = usePlayers()

  return (
    <div className="min-h-dvh">
      {/* Yükseklik sabit (h-14): PersistentPlayers içerik alanını kaplarken
          bu değere dayanıyor. Değiştirirseniz oradaki top-14 de değişmeli. */}
      <header className="flex h-14 items-center justify-between gap-4 border-b px-5">
        <div className="flex items-center gap-6">
          <span className="font-semibold">Yayın Merkezi</span>
          <nav className="flex gap-1">
            {NAV.filter((item) => !item.roles || hasRole(...item.roles)).map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  cn(
                    'relative inline-flex items-center gap-2 rounded-md px-3 py-1.5 text-sm transition-colors',
                    isActive
                      ? // Aktif sekme: paletteki açık mavi hem yazıda hem alt
                        // çizgide. Yalnızca zemin değiştirmek, yan yana duran
                        // sekmelerde hangisinin seçili olduğunu zayıf anlatıyordu.
                        'bg-accent text-primary-light after:absolute after:inset-x-3 after:bottom-0 after:h-0.5 after:rounded-full after:bg-primary-light'
                      : 'text-muted-foreground hover:bg-accent hover:text-foreground',
                  )
                }
              >
                <item.icon className="size-4" />
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>

        <div className="flex items-center gap-3 text-sm">
          <span className="text-muted-foreground">{session?.username}</span>
          {/* Yönetici mor vurguyla ayrışıyor; diğer roller sakin kalıyor. */}
          <Badge variant={session?.role === 'Yönetici' ? 'role' : 'outline'}>
            {session?.role ?? 'rolsüz'}
          </Badge>
          <Button variant="ghost" size="icon" title="Çıkış" onClick={() => void onLogout()}>
            <LogOutIcon />
          </Button>
        </div>
      </header>

      {/* Radyo çubuğu sabit konumlu ve sayfanın altını kaplıyor; alt boşluk
          olmasaydı son satır çubuğun arkasında kalır ve okunamazdı. */}
      <main className={cn('p-6', radioId && 'pb-24')}>
        <Outlet />
      </main>

      <PersistentPlayers />
      <PersistentRadio
        radioId={radioId}
        paused={radioPaused}
        onTogglePause={toggleRadioPause}
        onStop={stopRadio}
      />
    </div>
  )
}
