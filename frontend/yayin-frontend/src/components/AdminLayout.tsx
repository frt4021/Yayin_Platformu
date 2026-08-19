import { useEffect, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  ActivityIcon,
  ArrowLeftIcon,
  BarChart3Icon,
  ChevronLeftIcon,
  ChevronRightIcon,
  LayoutDashboardIcon,
  LogOutIcon,
  ScrollTextIcon,
  ShieldIcon,
  UsersIcon,
} from 'lucide-react'
import { WATCH_PATH } from '@/player/PersistentPlayers'

const NAV = [
  { to: '/yonetim/genel-bakis', label: 'Genel Bakış', icon: LayoutDashboardIcon },
  { to: '/yonetim/kullanicilar', label: 'Kullanıcılar', icon: UsersIcon },
  { to: '/yonetim/etkinlikler', label: 'Etkinlikler', icon: ActivityIcon },
  { to: '/yonetim/analitik', label: 'Analitik', icon: BarChart3Icon },
  { to: '/yonetim/sistem-loglari', label: 'Sistem Logları', icon: ScrollTextIcon },
]

/** Daraltma tercihi tarayıcıda kalıcı — izleme uygulamasının kendi anahtarından ayrı. */
const SIDEBAR_DARALT_KEY = 'yayin-merkezi:admin-sidebar-daraltildi'

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
 *
 * <p>Sol çubuk izleme uygulamasınınkiyle (bkz. {@code AppLayout.Shell})
 * <b>aynı görsel dil</b>: daraltılabilir, aktif öge tam kapsül, ikon
 * chiclet'leri tam yuvarlak. İki kabuk ayrı olsa da aynı uygulamanın
 * parçaları — sidebar birbirinden çok farklı görünürse "başka bir ürüne
 * geçtim" hissi verirdi.
 */
export function AdminLayout() {
  const { session, logout } = useAuth()
  const navigate = useNavigate()
  const [daraltildi, setDaraltildi] = useState(
    () => localStorage.getItem(SIDEBAR_DARALT_KEY) === '1',
  )

  useEffect(() => {
    localStorage.setItem(SIDEBAR_DARALT_KEY, daraltildi ? '1' : '0')
  }, [daraltildi])

  async function onLogout() {
    await logout()
    navigate('/giris', { replace: true })
  }

  return (
    <div className="min-h-dvh">
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-20 flex flex-col border-r bg-panel transition-[width] duration-200',
          daraltildi ? 'w-16' : 'w-60',
        )}
      >
        {/* Grip: kenarda yarı taşan yuvarlak düğme — AppLayout'takiyle aynı. */}
        <button
          type="button"
          onClick={() => setDaraltildi((v) => !v)}
          title={daraltildi ? 'Kenar çubuğunu genişlet' : 'Kenar çubuğunu daralt'}
          className="absolute -right-3 top-20 z-30 grid size-6 place-items-center rounded-full border bg-panel text-muted-foreground shadow-sm transition-colors hover:text-foreground"
        >
          {daraltildi ? (
            <ChevronRightIcon className="size-3.5" />
          ) : (
            <ChevronLeftIcon className="size-3.5" />
          )}
        </button>

        <div
          className={cn(
            'flex items-center gap-2.5 px-5 py-5',
            daraltildi && 'justify-center px-0',
          )}
        >
          <span className="grid size-9 shrink-0 place-items-center rounded-full bg-accent text-primary">
            <ShieldIcon className="size-5" />
          </span>
          {!daraltildi && <span className="text-lg font-semibold tracking-tight">Yönetim Paneli</span>}
        </div>

        <nav className={cn('min-h-0 flex-1 space-y-1.5 overflow-y-auto py-2', daraltildi ? 'px-2' : 'px-3')}>
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              title={daraltildi ? item.label : undefined}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-full py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
                  daraltildi ? 'justify-center px-0' : 'px-4',
                  isActive
                    ? 'bg-primary font-medium text-primary-foreground shadow-[0_4px_14px_-4px_var(--primary)]'
                    : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
                )
              }
            >
              <item.icon className="size-4.5 shrink-0" />
              {!daraltildi && item.label}
            </NavLink>
          ))}
        </nav>

        <div className={cn('border-t px-3 py-3', daraltildi && 'px-2')}>
          <NavLink
            to={WATCH_PATH}
            title={daraltildi ? 'İzleme uygulamasına dön' : undefined}
            className={cn(
              'flex items-center gap-3 rounded-full py-2.5 text-sm text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
              daraltildi ? 'justify-center px-0' : 'px-4',
            )}
          >
            <ArrowLeftIcon className="size-4.5 shrink-0" />
            {!daraltildi && 'İzleme uygulamasına dön'}
          </NavLink>
        </div>

        <div className="border-t px-3 py-4">
          <div className={cn('flex items-center gap-2.5 px-2', daraltildi && 'justify-center px-0')}>
            {!daraltildi && (
              <span className="min-w-0 flex-1 truncate text-sm">{session?.username}</span>
            )}
            <Button variant="ghost" size="icon" title="Çıkış" onClick={() => void onLogout()}>
              <LogOutIcon />
            </Button>
          </div>
          {!daraltildi && (
            <Badge variant="role" className="ml-2 mt-2">
              {session?.role ?? 'rolsüz'}
            </Badge>
          )}
        </div>
      </aside>

      <main
        className={cn('p-6 transition-[margin-left] duration-200', daraltildi ? 'ml-16' : 'ml-60')}
      >
        <Outlet />
      </main>
    </div>
  )
}
