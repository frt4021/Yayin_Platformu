import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  AudioLinesIcon,
  ClapperboardIcon,
  CompassIcon,
  FilmIcon,
  HistoryIcon,
  ImageIcon,
  LogOutIcon,
  MonitorIcon,
  RadioTowerIcon,
  UserIcon,
  UsersIcon,
} from 'lucide-react'
import type { Role } from '@/api/types'
import { PlayerProvider, usePlayers } from '@/player/PlayerContext'
import { PersistentPlayers, WATCH_PATH } from '@/player/PersistentPlayers'
import { PersistentRadio } from '@/player/PersistentRadio'
import { ActiveStreamPanel } from '@/player/ActiveStreamPanel'

interface NavItem {
  to: string
  label: string
  icon: typeof UsersIcon
  /** Tanımlıysa yalnızca bu rollere gösterilir. */
  roles?: Role[]
}

/**
 * Yan gezinme.
 *
 * <p><b>İkonlar geri geldi.</b> Üst çubukta yan yana dizilirken satırı
 * dolduruyor ve etiketle yarışıyorlardı; dikey listede her satırın başında
 * durunca göz taraması hızlanıyor ve hizalanmış bir sütun oluşuyor.
 *
 * <p><b>Profil listede değil</b>, en alttaki hesap bloğunda: içerik sekmesi
 * değil, hesapla ilgili.
 */
const NAV: NavItem[] = [
  { to: WATCH_PATH, label: 'İzle', icon: CompassIcon },
  { to: '/kanallar', label: 'Kanallar', icon: MonitorIcon },
  { to: '/radyolar', label: 'Radyolar', icon: AudioLinesIcon },
  { to: '/geriye-sarma', label: 'Geriye sarma', icon: HistoryIcon },
  { to: '/klipler', label: 'Klipler', icon: ClapperboardIcon },
  { to: '/videolar', label: 'Videolar', icon: FilmIcon },
  { to: '/galeri', label: 'Galeri', icon: ImageIcon },
  { to: '/yonetim/kullanicilar', label: 'Kullanıcılar', icon: UsersIcon, roles: ['Yönetici'] },
]

/** Yan çubuk genişliği. PersistentPlayers içerik alanını kaplarken buna dayanıyor. */
export const SIDEBAR_W = 'w-60'

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
  const location = useLocation()
  // Sag panel yalnizca izleme sayfasinda: diger sayfalarda karo yok ve panel
  // hicbir seye karsilik gelmeyen bos bir sutun olarak dururdu.
  const izlemede = location.pathname === WATCH_PATH

  return (
    <div className="min-h-dvh">
      {/* --- Sol yan çubuk ---
          Sabit konumlu: sayfa kaydırılırken gezinme yerinde kalmalı. Üst
          çubuktan buraya taşındı çünkü dokuz öğe yatayda satırı dolduruyordu
          ve sağdaki yayın paneline yer kalmıyordu. */}
      <aside className="fixed inset-y-0 left-0 z-20 flex w-60 flex-col border-r bg-panel">
        <NavLink to={WATCH_PATH} className="flex items-center gap-2.5 px-5 py-5">
          <span className="grid size-9 place-items-center rounded-xl bg-accent text-primary">
            <RadioTowerIcon className="size-5" />
          </span>
          <span className="text-lg font-semibold tracking-tight">Yayın Merkezi</span>
        </NavLink>

        <nav className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-2">
          {NAV.filter((item) => !item.roles || hasRole(...item.roles)).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition-colors',
                  isActive
                    ? // Aktif satır: dolu zemin + sol kenarda nane çizgi.
                      // Dikey listede alt çizgi işe yaramıyor; satırın
                      // tamamının vurgulanması gerekiyor.
                      'relative bg-accent font-medium text-foreground before:absolute before:inset-y-2 before:left-0 before:w-0.5 before:rounded-full before:bg-primary'
                    : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
                )
              }
            >
              <item.icon className="size-4.5 shrink-0" />
              {item.label}
            </NavLink>
          ))}
        </nav>

        {/* Hesap bloğu en altta: gezinme öğesi değil, oturum bilgisi. */}
        <div className="border-t px-3 py-4">
          <div className="flex items-center gap-2.5 px-2">
            <NavLink
              to="/profil"
              title="Profilim"
              className="grid size-8 shrink-0 place-items-center rounded-full bg-secondary text-muted-foreground transition-colors hover:text-foreground"
            >
              <UserIcon className="size-4" />
            </NavLink>
            <span className="min-w-0 flex-1 truncate text-sm">{session?.username}</span>
            <Button variant="ghost" size="icon" title="Çıkış" onClick={() => void onLogout()}>
              <LogOutIcon />
            </Button>
          </div>
          <Badge variant={session?.role ? 'role' : 'outline'} className="ml-11 mt-2">
            {session?.role ?? 'rolsüz'}
          </Badge>
        </div>
      </aside>

      {/* --- Orta sütun ---
          Kenar boşlukları yan çubukların GENİŞLİĞİ kadar: onlar sabit
          konumlu olduğu için akıştan çıkmış durumdalar ve içerik altlarına
          kayardı. */}
      <main className={cn('ml-60 p-6', izlemede && 'mr-80', radioId && 'pb-24')}>
        <Outlet />
      </main>

      {izlemede && <ActiveStreamPanel />}

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
