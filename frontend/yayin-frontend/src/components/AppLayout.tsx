import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  ClapperboardIcon,
  HistoryIcon,
  LogOutIcon,
  MonitorPlayIcon,
  RadioIcon,
  UserIcon,
  UsersIcon,
} from 'lucide-react'
import type { Role } from '@/api/types'
import { PlayerProvider } from '@/player/PlayerContext'
import { PersistentPlayers, WATCH_PATH } from '@/player/PersistentPlayers'

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
  { to: '/geriye-sarma', label: 'Geriye sarma', icon: HistoryIcon },
  { to: '/klipler', label: 'Klipler', icon: ClapperboardIcon },
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
    // PlayerProvider ve PersistentPlayers bilerek <Outlet/>'in DIŞINDA:
    // sayfa değiştiğinde unmount olmasınlar, yayın ve ses kesilmesin.
    <PlayerProvider>
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
                    'inline-flex items-center gap-2 rounded-md px-3 py-1.5 text-sm transition-colors',
                    isActive
                      ? 'bg-secondary text-secondary-foreground'
                      : 'text-muted-foreground hover:bg-accent hover:text-accent-foreground',
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
          <Badge variant="outline">{session?.role ?? 'rolsüz'}</Badge>
          <Button variant="ghost" size="icon" title="Çıkış" onClick={() => void onLogout()}>
            <LogOutIcon />
          </Button>
        </div>
      </header>

      <main className="p-6">
        <Outlet />
      </main>

      <PersistentPlayers />
    </div>
    </PlayerProvider>
  )
}
