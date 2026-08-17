import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { profileApi } from '@/api/endpoints'
import { formatBytes } from '@/api/upload'
import type { UserDto, QuotaUsage } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { CompassIcon, HelpCircleIcon, Loader2Icon } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { WATCH_TOUR_SEEN_KEY } from '@/components/tour/steps'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { PROFILE_TOUR_SEEN_KEY, PROFILE_TOUR_STEPS } from '@/components/tour/profileSteps'

export function ProfilePage() {
  const navigate = useNavigate()
  const [profile, setProfile] = useState<UserDto | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [repeatPassword, setRepeatPassword] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const tur = usePageTour(PROFILE_TOUR_SEEN_KEY)

  useEffect(() => {
    profileApi
      .me()
      .then(setProfile)
      .catch((e) => setLoadError(e instanceof ApiError ? e.message : 'Profil yüklenemedi.'))
  }, [])

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    // Tekrar alanı yalnızca yazım hatasını yakalar; backend böyle bir alan
    // beklemiyor, bu yüzden kontrol burada yapılıp gönderilmiyor.
    if (newPassword !== repeatPassword) {
      setFormError('Yeni şifreler eşleşmiyor.')
      return
    }
    setFormError(null)
    setBusy(true)
    try {
      await profileApi.changePassword(currentPassword, newPassword)
      toast.success('Şifreniz değiştirildi.')
      setCurrentPassword('')
      setNewPassword('')
      setRepeatPassword('')
    } catch (e) {
      setFormError(e instanceof ApiError ? e.message : 'Şifre değiştirilemedi.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex max-w-2xl flex-col gap-5">
      <div>
        <div className="flex items-center gap-2">
          <h1 className="text-3xl font-semibold tracking-tight">Profilim</h1>
          <TourTrigger onClick={tur.start} />
        </div>
        <p className="text-sm text-muted-foreground">Hesap bilgileriniz ve şifre değişikliği.</p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Rehberli tur</CardTitle>
          <CardDescription>
            Arayüzün nasıl çalıştığını adım adım gösterir. Her sayfanın kendi turu var —
            ilk girişte bir kez kendiliğinden açılır, sayfadaki{' '}
            <span className="inline-flex items-center gap-1 align-middle">
              <HelpCircleIcon className="size-3.5" />
            </span>{' '}
            simgesinden istediğiniz zaman yeniden başlatabilirsiniz. Aşağıdaki düğme İzleme
            sayfasının turunu açar.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <Button
            variant="outline"
            onClick={() => {
              // Once "gorundu" isareti siliniyor: yalnizca olay yayilsaydi
              // tur acilirdi ama isaret durdugu icin bir dahaki girise
              // yine kendiliginden acilmazdi -- kullanici "yeniden baslat"
              // dedigine gore o davranisi da geri istiyor.
              localStorage.removeItem(WATCH_TOUR_SEEN_KEY)
              // Hedeflerin cogu Izleme sayfasinda; once oraya gidiliyor.
              navigate('/izle')
              window.dispatchEvent(new Event('yayin-merkezi:tur'))
            }}
          >
            <CompassIcon />
            İzleme sayfası turunu başlat
          </Button>
        </CardContent>
      </Card>

      <Card data-tour="profil-hesap">
        <CardHeader>
          <CardTitle className="text-base">Hesap bilgileri</CardTitle>
        </CardHeader>
        <CardContent>
          {loadError && <p className="text-sm text-destructive">{loadError}</p>}
          {!loadError && !profile && <Loader2Icon className="animate-spin" />}
          {profile && (
            <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
              <dt className="text-muted-foreground">Kullanıcı adı</dt>
              <dd>{profile.username}</dd>
              <dt className="text-muted-foreground">E-posta</dt>
              <dd>{profile.email ?? '—'}</dd>
              <dt className="text-muted-foreground">Ad Soyad</dt>
              <dd>{[profile.firstName, profile.lastName].filter(Boolean).join(' ') || '—'}</dd>
              <dt className="text-muted-foreground">Rol</dt>
              <dd>
                <Badge variant="secondary">{profile.role}</Badge>
              </dd>
            </dl>
          )}
        </CardContent>
      </Card>

      <Card data-tour="profil-sifre">
        <CardHeader>
          <CardTitle className="text-base">Şifre değiştir</CardTitle>
          <CardDescription>Güvenlik için mevcut şifreniz doğrulanır.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="currentPassword">Mevcut şifre</Label>
              <Input
                id="currentPassword"
                type="password"
                required
                autoComplete="current-password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="newPassword">Yeni şifre</Label>
              <Input
                id="newPassword"
                type="password"
                required
                minLength={8}
                autoComplete="new-password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-2">
              <Label htmlFor="repeatPassword">Yeni şifre (tekrar)</Label>
              <Input
                id="repeatPassword"
                type="password"
                required
                minLength={8}
                autoComplete="new-password"
                value={repeatPassword}
                onChange={(e) => setRepeatPassword(e.target.value)}
              />
            </div>

            {formError && (
              <p role="alert" className="text-sm text-destructive">
                {formError}
              </p>
            )}

            <Button type="submit" disabled={busy} className="self-start">
              {busy && <Loader2Icon className="animate-spin" />}
              Şifreyi değiştir
            </Button>
          </form>
        </CardContent>
      </Card>
      <QuotaCard />

      <GuidedTour open={tur.open} onClose={tur.close} steps={PROFILE_TOUR_STEPS} />
    </div>
  )
}

/**
 * Depolama kullanımı.
 *
 * <p>Klip, kayıt, ekran görüntüsü ve video toplamını gösteriyor. Kota
 * dolduğunda yeni iş reddediliyor ama var olan silinmiyor — ne silineceğine
 * kullanıcı karar veriyor, o yüzden dağılımı görmesi gerekiyor.
 */
export function QuotaCard() {
  const [usage, setUsage] = useState<QuotaUsage | null>(null)

  useEffect(() => {
    void profileApi.quota().then(setUsage).catch(() => {})
  }, [])

  if (!usage) return null

  const kalemler: [string, number][] = [
    ['Klip ve kayıtlar', usage.clipBytes],
    ['Ekran görüntüleri', usage.screenshotBytes],
    ['Videolar', usage.videoBytes],
  ]

  return (
    <div data-tour="profil-kota" className="flex flex-col gap-3 rounded-xl border p-4">
      <div className="flex items-center justify-between">
        <h2 className="font-medium">Depolama</h2>
        <span className="text-sm text-muted-foreground">
          {usage.unlimited
            ? `${formatBytes(usage.totalBytes)} · sınırsız`
            : `${formatBytes(usage.totalBytes)} / ${formatBytes(usage.quotaBytes)}`}
        </span>
      </div>

      {!usage.unlimited && (
        <div className="h-2 overflow-hidden rounded-full bg-secondary">
          <div
            className={
              usage.percentUsed >= 90
                ? 'h-full rounded-full bg-status-error'
                : usage.percentUsed >= 70
                  ? 'h-full rounded-full bg-status-warning'
                  : 'h-full rounded-full bg-primary'
            }
            style={{ width: `${usage.percentUsed}%` }}
          />
        </div>
      )}

      <dl className="grid grid-cols-3 gap-2 text-sm">
        {kalemler.map(([ad, bayt]) => (
          <div key={ad}>
            <dt className="text-xs text-muted-foreground">{ad}</dt>
            <dd className="font-medium">{formatBytes(bayt)}</dd>
          </div>
        ))}
      </dl>

      {!usage.unlimited && usage.percentUsed >= 90 && (
        <p className="text-xs text-status-error">
          Kota dolmak üzere. Yeni klip, kayıt veya yükleme reddedilebilir —
          yer açmak için eski kayıtları silin.
        </p>
      )}
    </div>
  )
}
