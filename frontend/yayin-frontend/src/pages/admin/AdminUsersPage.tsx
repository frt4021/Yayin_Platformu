import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { adminUsersApi } from '@/api/endpoints'
import { ROLES, type Role, type UserDto } from '@/api/types'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { KeyRoundIcon, Loader2Icon, RefreshCwIcon, Trash2Icon, UserPlusIcon } from 'lucide-react'
import { CreateUserDialog } from './CreateUserDialog'
import { ResetPasswordDialog } from './ResetPasswordDialog'
import { UserActivityDialog } from './UserActivityDialog'

function roleVariant(role: Role) {
  if (role === 'Yönetici') return 'default' as const
  if (role === 'Moderatör') return 'secondary' as const
  return 'outline' as const
}

export function AdminUsersPage() {
  const { session } = useAuth()

  const [users, setUsers] = useState<UserDto[]>([])
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [resetTarget, setResetTarget] = useState<UserDto | null>(null)
  const [activityTarget, setActivityTarget] = useState<UserDto | null>(null)
  /** İşlem süren satırların id'si — o satırın düğmelerini kilitler. */
  const [pending, setPending] = useState<Set<string>>(new Set())

  const load = useCallback(async (term: string) => {
    setLoading(true)
    setError(null)
    try {
      setUsers(await adminUsersApi.list(term || undefined))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Kullanıcılar yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  // Arama kutusunda her tuşa basışta istek atmamak için gecikme.
  useEffect(() => {
    const timer = setTimeout(() => void load(search), 300)
    return () => clearTimeout(timer)
  }, [search, load])

  async function withPending(id: string, action: () => Promise<void>) {
    setPending((prev) => new Set(prev).add(id))
    try {
      await action()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'İşlem başarısız.')
    } finally {
      setPending((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
    }
  }

  function changeRole(user: UserDto, role: Role) {
    if (role === user.role) return
    return withPending(user.id, async () => {
      const updated = await adminUsersApi.changeRole(user.id, role)
      setUsers((prev) => prev.map((u) => (u.id === user.id ? updated : u)))
      toast.success(`${user.username} rolü ${role} olarak güncellendi.`)
    })
  }

  function remove(user: UserDto) {
    if (!confirm(`${user.username} kalıcı olarak silinecek. Emin misiniz?`)) return
    return withPending(user.id, async () => {
      await adminUsersApi.remove(user.id)
      setUsers((prev) => prev.filter((u) => u.id !== user.id))
      toast.success(`${user.username} silindi.`)
    })
  }

  async function sync() {
    try {
      const result = await adminUsersApi.sync()
      toast.success(
        `Eşitlendi: ${result.created.length} eklendi, ${result.updated.length} güncellendi.`,
        result.orphaned.length
          ? { description: `Keycloak'ta bulunmayan ${result.orphaned.length} yerel kayıt var: ${result.orphaned.join(', ')}` }
          : undefined,
      )
      await load(search)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Eşitleme başarısız.')
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">Kullanıcılar</h1>
          <p className="text-sm text-muted-foreground">
            Kullanıcı ekleyin, rol atayın, şifre sıfırlayın.
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={sync} title="Keycloak'taki değişiklikleri yerel tabloya yansıt">
            <RefreshCwIcon />
            Eşitle
          </Button>
          <Button onClick={() => setCreateOpen(true)}>
            <UserPlusIcon />
            Yeni kullanıcı
          </Button>
        </div>
      </div>

      <Input
        placeholder="Kullanıcı adı, ad, soyad veya e-posta ara…"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="max-w-sm"
      />

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div className="rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Kullanıcı</TableHead>
                <TableHead>E-posta</TableHead>
                <TableHead>Ad Soyad</TableHead>
                <TableHead>Rol</TableHead>
                <TableHead>Durum</TableHead>
                <TableHead className="text-right">İşlem</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                    <Loader2Icon className="mx-auto animate-spin" />
                  </TableCell>
                </TableRow>
              )}

              {!loading && users.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                    Kullanıcı bulunamadı.
                  </TableCell>
                </TableRow>
              )}

              {!loading &&
                users.map((user) => {
                  const busy = pending.has(user.id)
                  const isSelf = user.username === session?.username
                  return (
                    <TableRow key={user.id}>
                      <TableCell className="font-medium">
                        <button
                          type="button"
                          title="Kullanıcı aktivitesini görüntüle"
                          className="hover:underline"
                          onClick={() => setActivityTarget(user)}
                        >
                          {user.username}
                        </button>
                        {isSelf && (
                          <span className="ml-2 text-xs text-muted-foreground">(siz)</span>
                        )}
                      </TableCell>
                      <TableCell className="text-muted-foreground">{user.email ?? '—'}</TableCell>
                      <TableCell className="text-muted-foreground">
                        {[user.firstName, user.lastName].filter(Boolean).join(' ') || '—'}
                      </TableCell>
                      <TableCell>
                        <Select
                          value={user.role}
                          disabled={busy}
                          onValueChange={(value) => void changeRole(user, value as Role)}
                        >
                          <SelectTrigger className="h-8 w-36">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {ROLES.map((role) => (
                              <SelectItem key={role} value={role}>
                                {role}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </TableCell>
                      <TableCell>
                        <Badge variant={user.enabled ? roleVariant(user.role) : 'destructive'}>
                          {user.enabled ? 'Aktif' : 'Kapalı'}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="icon"
                            disabled={busy}
                            title="Şifre sıfırla"
                            onClick={() => setResetTarget(user)}
                          >
                            <KeyRoundIcon />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            // Backend kendi hesabını silmeyi zaten reddediyor;
                            // düğmeyi kapatarak boşuna hata almayı önlüyoruz.
                            disabled={busy || isSelf}
                            title={isSelf ? 'Kendi hesabınızı silemezsiniz' : 'Sil'}
                            onClick={() => void remove(user)}
                          >
                            <Trash2Icon className="text-destructive" />
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  )
                })}
            </TableBody>
          </Table>
        </div>
      )}

      <CreateUserDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={() => void load(search)}
      />
      <ResetPasswordDialog user={resetTarget} onClose={() => setResetTarget(null)} />
      <UserActivityDialog user={activityTarget} onClose={() => setActivityTarget(null)} />
    </div>
  )
}
