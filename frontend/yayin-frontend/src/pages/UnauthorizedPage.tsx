import { Link } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/auth/AuthContext'

export function UnauthorizedPage() {
  const { session } = useAuth()
  return (
    <div className="grid min-h-dvh place-items-center p-6 text-center">
      <div className="flex flex-col items-center gap-3">
        <h1 className="text-3xl font-semibold tracking-tight">Bu sayfaya erişiminiz yok</h1>
        <p className="text-sm text-muted-foreground">
          Rolünüz: <strong>{session?.role ?? 'atanmamış'}</strong>. Erişim için yöneticinizden
          rol değişikliği isteyin.
        </p>
        <Button asChild variant="outline">
          <Link to="/">Ana sayfaya dön</Link>
        </Button>
      </div>
    </div>
  )
}
