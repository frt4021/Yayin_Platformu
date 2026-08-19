import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import {
  AudioLinesIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
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
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { WATCH_TOUR_SEEN_KEY, WATCH_TOUR_STEPS } from '@/components/tour/steps'

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

/** Daraltılmış (yalnızca ikon) yan çubuk genişliği. */
const SIDEBAR_W_DAR = 'w-16'

/** Daraltma tercihi tarayıcıda kalıcı — sayfa değiştikçe sıfırlanmamalı. */
const SIDEBAR_DARALT_KEY = 'yayin-merkezi:sidebar-daraltildi'

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
  const [daraltildi, setDaraltildi] = useState(
    () => localStorage.getItem(SIDEBAR_DARALT_KEY) === '1',
  )

  useEffect(() => {
    localStorage.setItem(SIDEBAR_DARALT_KEY, daraltildi ? '1' : '0')
  }, [daraltildi])
  // Sag panel yalnizca izleme sayfasinda: diger sayfalarda karo yok ve panel
  // hicbir seye karsilik gelmeyen bos bir sutun olarak dururdu.
  const izlemede = location.pathname === WATCH_PATH

  /**
   * İzleme sayfasının turu.
   *
   * <p>Hedeflerin çoğu bu sayfada olduğu için otomatik açılış yalnızca
   * burada gerçekleşiyor — {@code izlemede} ile açık prop'u bastırılıyor,
   * başka bir sayfadayken gösterilseydi adımların yarısı hedefsiz kalıp
   * atlanırdı. {@code Shell} rota değişse de unmount olmadığı için
   * {@code usePageTour}'un kendi zamanlayıcısı yalnızca bir kez çalışıyor.
   */
  const izlemeTuru = usePageTour(WATCH_TOUR_SEEN_KEY)

  useEffect(() => {
    const ac = () => izlemeTuru.start()
    // Profil sayfasindaki "turu yeniden baslat" dugmesi bu olayi yayiyor.
    // Context yerine olay: tur tek bir yerden aciliyor ve araya bir saglayici
    // koymak, yalnizca bunun icin tum agaci sarmalamak olurdu.
    window.addEventListener('yayin-merkezi:tur', ac)
    return () => window.removeEventListener('yayin-merkezi:tur', ac)
  }, [izlemeTuru])

  return (
    <div className="min-h-dvh">
      {/* --- Sol yan çubuk ---
          Sabit konumlu: sayfa kaydırılırken gezinme yerinde kalmalı. Üst
          çubuktan buraya taşındı çünkü dokuz öğe yatayda satırı dolduruyordu
          ve sağdaki yayın paneline yer kalmıyordu. */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-20 flex flex-col border-r bg-panel transition-[width] duration-200',
          daraltildi ? SIDEBAR_W_DAR : SIDEBAR_W,
        )}
      >
        {/* Grip: kenarda yarı taşan yuvarlak düğme — daraltma/genişletme burada. */}
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

        <NavLink
          to={WATCH_PATH}
          className={cn(
            'flex items-center gap-2.5 rounded-xl px-5 py-5 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
            daraltildi && 'justify-center px-0',
          )}
        >
          <span className="grid size-9 shrink-0 place-items-center rounded-full bg-accent text-primary">
            <RadioTowerIcon className="size-5" />
          </span>
          {!daraltildi && <span className="text-lg font-semibold tracking-tight">Yayın Merkezi</span>}
        </NavLink>

        {/* Aktif öge tüm satırı kaplayan bir kapsül (stadium şekli) —
            yalnızca ikonun etrafında değil, ikon+etiketin ikisini birden
            saran dolu bir oval zemin. */}
        <nav
          data-tour="nav"
          className={cn('min-h-0 flex-1 space-y-1.5 overflow-y-auto py-2', daraltildi ? 'px-2' : 'px-3')}
        >
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              title={daraltildi ? item.label : undefined}
              className={({ isActive }) =>
                cn(
                  // focus-visible: klavyeyle (Tab) gelindiğinde hangi satırda
                  // olunduğu belli olsun diye — hover'ın aksine hiçbir zaman
                  // örtülü değildi, fare kullanılmayan gezinmede satır sırf
                  // ok tuşlarıyla takip edilemiyordu.
                  'flex items-center gap-3 rounded-full py-2.5 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
                  daraltildi ? 'justify-center px-0' : 'px-4',
                  isActive
                    ? 'bg-primary font-medium text-primary-foreground shadow-[0_4px_14px_-4px_var(--primary)]'
                    : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
                )
              }
            >
              <item.icon className="size-4.5 shrink-0" />
              {!daraltildi && <span className="truncate">{item.label}</span>}
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
              title={daraltildi ? 'Yönetim Paneline Git' : undefined}
              className={cn(
                'flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)] focus-visible:ring-inset',
                daraltildi && 'justify-center px-0',
              )}
            >
              <ShieldIcon className="size-4.5 shrink-0" />
              {!daraltildi && 'Yönetim Paneline Git'}
            </NavLink>
          </div>
        )}

        {/* Hesap bloğu en altta: gezinme öğesi değil, oturum bilgisi. */}
        <div data-tour="hesap" className="border-t px-3 py-4">
          <div className={cn('flex items-center gap-2.5 px-2', daraltildi && 'justify-center px-0')}>
            <NavLink
              to="/profil"
              title="Profilim"
              className="grid size-8 shrink-0 place-items-center rounded-full bg-secondary text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            >
              <UserIcon className="size-4" />
            </NavLink>
            {!daraltildi && (
              <>
                <span className="min-w-0 flex-1 truncate text-sm">{session?.username}</span>
                <Button variant="ghost" size="icon" title="Çıkış" onClick={() => void onLogout()}>
                  <LogOutIcon />
                </Button>
              </>
            )}
          </div>
          {!daraltildi && (
            <Badge variant={session?.role ? 'role' : 'outline'} className="ml-11 mt-2">
              {session?.role ?? 'rolsüz'}
            </Badge>
          )}
        </div>
      </aside>

      {/* --- Orta sütun ---
          Kenar boşlukları yan çubukların GENİŞLİĞİ kadar: onlar sabit
          konumlu olduğu için akıştan çıkmış durumdalar ve içerik altlarına
          kayardı. */}
      <main
        className={cn(
          'p-6 transition-[margin-left] duration-200',
          daraltildi ? 'ml-16' : 'ml-60',
          radioId && 'pb-24',
        )}
      >
        <Outlet />
      </main>

      {izlemede && <TourTrigger onClick={izlemeTuru.start} />}

      <GuidedTour
        open={izlemede && izlemeTuru.open}
        onClose={izlemeTuru.close}
        steps={WATCH_TOUR_STEPS}
      />

      <PersistentPlayers sidebarCollapsed={daraltildi} />
      <PersistentRadio
        radioId={radioId}
        paused={radioPaused}
        onTogglePause={toggleRadioPause}
        onStop={stopRadio}
      />
    </div>
  )
}
