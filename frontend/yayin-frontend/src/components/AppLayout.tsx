import { useEffect, useState } from 'react'
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
  ShieldIcon,
  UserIcon,
} from 'lucide-react'
import { PlayerProvider, usePlayers } from '@/player/PlayerContext'
import { PersistentPlayers, WATCH_PATH } from '@/player/PersistentPlayers'
import { PersistentRadio } from '@/player/PersistentRadio'
import { ActiveStreamPanel } from '@/player/ActiveStreamPanel'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { TOUR_SEEN_KEY } from '@/components/tour/steps'

interface NavItem {
  to: string
  label: string
  icon: typeof CompassIcon
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
      <Shell session={session} isAdmin={hasRole('Yönetici')} onLogout={onLogout} />
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
  isAdmin,
  onLogout,
}: {
  session: ReturnType<typeof useAuth>['session']
  isAdmin: boolean
  onLogout: () => Promise<void>
}) {
  const { radioId, radioPaused, toggleRadioPause, stopRadio } = usePlayers()
  const location = useLocation()
  // Sag panel yalnizca izleme sayfasinda: diger sayfalarda karo yok ve panel
  // hicbir seye karsilik gelmeyen bos bir sutun olarak dururdu.
  const izlemede = location.pathname === WATCH_PATH

  /**
   * Rehberli tur.
   *
   * <p>İlk girişte <b>bir kez</b> açılıyor. Hedeflerin çoğu İzleme
   * sayfasında olduğu için yalnızca orada başlatılıyor — başka bir sayfada
   * açılsaydı adımların yarısı hedefsiz kalıp atlanırdı.
   *
   * <p>Gecikme, hedeflerin DOM'a girmesini bekliyor: kanal listesi bir
   * istekten geliyor ve tur ondan önce ölçüm yaparsa çipleri bulamıyor.
   */
  const [turAcik, setTurAcik] = useState(false)

  useEffect(() => {
    if (!izlemede || localStorage.getItem(TOUR_SEEN_KEY)) return
    const timer = setTimeout(() => setTurAcik(true), 900)
    return () => clearTimeout(timer)
  }, [izlemede])

  useEffect(() => {
    const ac = () => setTurAcik(true)
    // Profil sayfasindaki "turu yeniden baslat" dugmesi bu olayi yayiyor.
    // Context yerine olay: tur tek bir yerden aciliyor ve araya bir saglayici
    // koymak, yalnizca bunun icin tum agaci sarmalamak olurdu.
    window.addEventListener('yayin-merkezi:tur', ac)
    return () => window.removeEventListener('yayin-merkezi:tur', ac)
  }, [])

  return (
    <div className="min-h-dvh">
      {/* --- Sol yan çubuk ---
          Sabit konumlu: sayfa kaydırılırken gezinme yerinde kalmalı. Üst
          çubuktan buraya taşındı çünkü dokuz öğe yatayda satırı dolduruyordu
          ve sağdaki yayın paneline yer kalmıyordu. */}
      <aside className="fixed inset-y-0 left-0 z-20 flex w-60 flex-col border-r bg-panel">
        <NavLink
          to={WATCH_PATH}
          className="flex items-center gap-2.5 rounded-xl px-5 py-5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset"
        >
          <span className="grid size-9 place-items-center rounded-xl bg-accent text-primary">
            <RadioTowerIcon className="size-5" />
          </span>
          <span className="text-lg font-semibold tracking-tight">Yayın Merkezi</span>
        </NavLink>

        <nav data-tour="nav" className="min-h-0 flex-1 space-y-1 overflow-y-auto px-3 py-2">
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                cn(
                  // focus-visible: klavyeyle (Tab) gelindiğinde hangi satırda
                  // olunduğu belli olsun diye — hover'ın aksine hiçbir zaman
                  // örtülü değildi, fare kullanılmayan gezinmede satır sırf
                  // ok tuşlarıyla takip edilemiyordu.
                  'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
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

        {/* Yalnızca Yönetici görür — Kullanıcılar/Etkinlikler/Analitik artık
            burada değil, tamamen ayrı bir kabukta ({@code AdminLayout}); bu
            tek düğme oraya giriş noktası. */}
        {isAdmin && (
          <div className="border-t px-3 py-3">
            <NavLink
              to="/yonetim/genel-bakis"
              className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset"
            >
              <ShieldIcon className="size-4.5 shrink-0" />
              Yönetim Paneline Git
            </NavLink>
          </div>
        )}

        {/* Hesap bloğu en altta: gezinme öğesi değil, oturum bilgisi. */}
        <div data-tour="hesap" className="border-t px-3 py-4">
          <div className="flex items-center gap-2.5 px-2">
            <NavLink
              to="/profil"
              title="Profilim"
              className="grid size-8 shrink-0 place-items-center rounded-full bg-secondary text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
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

      <GuidedTour
        open={turAcik}
        onClose={() => {
          setTurAcik(false)
          localStorage.setItem(TOUR_SEEN_KEY, '1')
        }}
      />

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
