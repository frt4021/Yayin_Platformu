import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  ActivityIcon,
  ArrowLeftIcon,
  BarChart3Icon,
  LayoutDashboardIcon,
  LogOutIcon,
  ShieldIcon,
  UsersIcon,
} from 'lucide-react'
import { WATCH_PATH } from '@/player/PersistentPlayers'

const NAV = [
  { to: '/yonetim/genel-bakis', label: 'Genel Bakış', icon: LayoutDashboardIcon },
  { to: '/yonetim/kullanicilar', label: 'Kullanıcılar', icon: UsersIcon },
  { to: '/yonetim/etkinlikler', label: 'Etkinlikler', icon: ActivityIcon },
  { to: '/yonetim/analitik', label: 'Analitik', icon: BarChart3Icon },
]

/**
 * Yönetim paneli — izleme uygulamasından TAMAMEN ayrı bir kabuk.
 *
 * <p><b>Neden ayrı, {@code AppLayout}'un içine gömülü değil:</b> Kullanıcılar/
 * Etkinlikler/Analitik daha önce izleme uygulamasının sol çubuğunda görünen
 * sıradan sayfalardı — yönetici olmayan hiçbir işlevleri yokken izleme
 * arayüzünün (mozaik oynatıcılar, radyo çubuğu, rehberli tur) her zaman
 * arka planda yaşamasına, ve normal kullanıcı gezinmesinde gereksiz yer
 * kaplamasına sebep oluyordu. Bu kabuğa yalnızca Yöneticiler, izleme
 * uygulamasının sol çubuğundaki "Yönetim Paneline Git" düğmesiyle
 * ({@code AppLayout}) ulaşıyor — kendi başına bir uygulama gibi davranıyor,
 * {@code PlayerProvider}/{@code PersistentPlayers}/{@code PersistentRadio}
 * bilerek burada YOK.
 */
export function AdminLayout() {
  const { session, logout } = useAuth()
  const navigate = useNavigate()

  async function onLogout() {
    await logout()
    navigate('/giris', { replace: true })
  }

  return (
    <div className="min-h-dvh">
      <aside className="fixed inset-y-0 left-0 z-20 flex w-60 flex-col border-r bg-panel">
        <div className="flex items-center gap-2.5 px-5 py-5">
          <span className="grid size-9 place-items-center rounded-xl bg-accent text-primary">
            <ShieldIcon className="size-5" />
          </span>
          <span className="text-lg font-semibold tracking-tight">Yönetim Paneli</span>
        </div>

        <nav className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-2">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
                  isActive
                    ? 'relative bg-accent font-medium text-foreground before:absolute before:inset-y-2 before:left-0 before:w-0.5 before:rounded-full before:bg-primary'
                    : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
                )
              }
            >
              <item.icon className="size-4.5 shrink-0" />
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="border-t px-3 py-3">
          <NavLink
            to={WATCH_PATH}
            className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset"
          >
            <ArrowLeftIcon className="size-4.5 shrink-0" />
            İzleme uygulamasına dön
          </NavLink>
        </div>

        <div className="border-t px-3 py-4">
          <div className="flex items-center gap-2.5 px-2">
            <span className="min-w-0 flex-1 truncate text-sm">{session?.username}</span>
            <Button variant="ghost" size="icon" title="Çıkış" onClick={() => void onLogout()}>
              <LogOutIcon />
            </Button>
          </div>
          <Badge variant="role" className="ml-2 mt-2">
            {session?.role ?? 'rolsüz'}
          </Badge>
        </div>
      </aside>

      <main className="ml-60 p-6">
        <Outlet />
      </main>
    </div>
  )
}
