import { useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { adminUsersApi } from '@/api/endpoints'
import type { UserDto } from '@/api/types'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Loader2Icon } from 'lucide-react'

export function ResetPasswordDialog({
  user,
  onClose,
}: {
  user: UserDto | null
  onClose: () => void
}) {
  const [password, setPassword] = useState('')
  const [temporary, setTemporary] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!user) return
    setError(null)
    setBusy(true)
    try {
      await adminUsersApi.resetPassword(user.id, password, temporary)
      toast.success(`${user.username} şifresi sıfırlandı.`)
      setPassword('')
      onClose()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Şifre sıfırlanamadı.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={user !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>Şifre sıfırla</DialogTitle>
          <DialogDescription>
            <strong>{user?.username}</strong> için yeni şifre. Mevcut şifre sorulmaz.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="newPassword">Yeni şifre</Label>
            <Input
              id="newPassword"
              type="password"
              required
              minLength={8}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <input
                type="checkbox"
                checked={temporary}
                onChange={(e) => setTemporary(e.target.checked)}
              />
              Geçici — kullanıcı ilk girişte değiştirsin
            </label>
          </div>

          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Vazgeç
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2Icon className="animate-spin" />}
              Sıfırla
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
