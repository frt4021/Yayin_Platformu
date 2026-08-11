
import { useState } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/auth/AuthContext'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2Icon, RadioTowerIcon } from 'lucide-react'

interface LocationState {
  from?: { pathname: string }
}

export function LoginPage() {
  const { session, login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (session) return <Navigate to="/" replace />

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(username, password)
      const target = (location.state as LocationState | null)?.from?.pathname ?? '/'
      navigate(target, { replace: true })
    } catch (e) {
      // Backend kullanıcı adının var olup olmadığını bilerek ayırmıyor;
      // mesajı olduğu gibi gösteriyoruz.
      setError(e instanceof ApiError ? e.message : 'Giriş yapılamadı.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid min-h-dvh place-items-center p-6">
      <Card className="w-full max-w-sm">
        <CardHeader>
          {/* Marka işareti üst çubuktakiyle aynı: giriş, uygulamanın ilk
              görülen ekranı ve burada işaret yoksa oturum açıldığında
              tanınmayan bir arayüze geçilmiş gibi oluyor. */}
          <span className="mb-1 grid size-11 place-items-center rounded-xl bg-accent text-primary">
            <RadioTowerIcon className="size-6" />
          </span>
          <CardTitle className="text-xl">Yayın Merkezi</CardTitle>
          <CardDescription>Devam etmek için giriş yapın.</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="username">Kullanıcı adı</Label>
              <Input
                id="username"
                autoComplete="username"
                autoFocus
                required
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              />
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="password">Şifre</Label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>

            {error && (
              <p role="alert" className="text-sm text-destructive">
                {error}
              </p>
            )}

            <Button type="submit" disabled={busy}>
              {busy && <Loader2Icon className="animate-spin" />}
              Giriş yap
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
