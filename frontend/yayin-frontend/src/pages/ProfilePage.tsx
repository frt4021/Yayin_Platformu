import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { profileApi } from '@/api/endpoints'
import type { UserDto } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2Icon } from 'lucide-react'

export function ProfilePage() {
  const [profile, setProfile] = useState<UserDto | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [repeatPassword, setRepeatPassword] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

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
        <h1 className="text-xl font-semibold">Profilim</h1>
        <p className="text-sm text-muted-foreground">Hesap bilgileriniz ve şifre değişikliği.</p>
      </div>

      <Card>
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

      <Card>
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
    </div>
  )
}
