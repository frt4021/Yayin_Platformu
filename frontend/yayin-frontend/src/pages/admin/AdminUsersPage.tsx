import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { adminUsersApi } from '@/api/endpoints'
import { ROLES, type Role, type UserDto } from '@/api/types'
import { useAuth } from '@/auth/AuthContext'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
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
import {
  KeyRoundIcon,
  Loader2Icon,
  RefreshCwIcon,
  SearchIcon,
  Trash2Icon,
  UserPlusIcon,
} from 'lucide-react'
import { CreateUserDialog } from './CreateUserDialog'
import { ResetPasswordDialog } from './ResetPasswordDialog'
import { UserActivityDialog } from './UserActivityDialog'
import { Sayfalama } from './Sayfalama'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import { ADMIN_USERS_TOUR_STEPS, ADMIN_USERS_TOUR_SEEN_KEY } from '@/components/tour/adminUsersSteps'

const MAX = 50

export function AdminUsersPage() {
  const { session } = useAuth()
  const tur = usePageTour(ADMIN_USERS_TOUR_SEEN_KEY)

  const [users, setUsers] = useState<UserDto[]>([])
  const [total, setTotal] = useState(0)
  const [first, setFirst] = useState(0)
  const [search, setSearch] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [resetTarget, setResetTarget] = useState<UserDto | null>(null)
  const [activityTarget, setActivityTarget] = useState<UserDto | null>(null)
  /** İşlem süren satırların id'si — o satırın düğmelerini kilitler. */
  const [pending, setPending] = useState<Set<string>>(new Set())

  const load = useCallback(async (term: string, f: number) => {
    setLoading(true)
    setError(null)
    try {
      const sayfa = await adminUsersApi.list(term || undefined, f, MAX)
      setUsers(sayfa.items)
      setTotal(sayfa.total)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Kullanıcılar yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  // Arama kutusunda her tuşa basışta istek atmamak için gecikme. Arama
  // değişince ilk sayfaya dönülüyor — aksi halde `first` yeni aramada
  // anlamsız bir kaydırma noktasına işaret ederdi.
  useEffect(() => {
    const timer = setTimeout(() => {
      setFirst(0)
      void load(search, 0)
    }, 300)
    return () => clearTimeout(timer)
  }, [search, load])

  function sayfaDegis(yeniFirst: number) {
    setFirst(yeniFirst)
    void load(search, yeniFirst)
  }

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
      setTotal((prev) => Math.max(0, prev - 1))
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
      await load(search, first)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Eşitleme başarısız.')
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight">Kullanıcılar</h1>
            <p className="text-sm text-muted-foreground">
              Kullanıcı ekleyin, rol atayın, şifre sıfırlayın.
            </p>
          </div>
          <TourTrigger onClick={tur.start} />
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="relative">
            <SearchIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
            <input
              data-tour="arama"
              type="search"
              placeholder="Kullanıcı adı, e-posta ara…"
              aria-label="Kullanıcı ara"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="h-10 w-64 rounded-full border bg-card pl-11 pr-4 text-sm
                         placeholder:text-muted-foreground focus:outline-none
                         focus:ring-2 focus:ring-[var(--ring)]"
            />
          </div>

          <Button
            data-tour="esitle"
            variant="secondary"
            className="rounded-full"
            onClick={sync}
            title="Keycloak'taki değişiklikleri yerel tabloya yansıt"
          >
            <RefreshCwIcon />
            Eşitle
          </Button>
          <Button data-tour="yeni-kullanici" className="rounded-full" onClick={() => setCreateOpen(true)}>
            <UserPlusIcon />
            Yeni kullanıcı
          </Button>
        </div>
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div data-tour="kullanici-tablosu" className="rounded-2xl border bg-panel shadow-sm">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="uppercase tracking-wide text-[11px]">Kullanıcı</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">E-posta</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Ad Soyad</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Rol</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Durum</TableHead>
                <TableHead data-tour="kullanici-islemler" className="text-right uppercase tracking-wide text-[11px]">
                  İşlem
                </TableHead>
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
                        <div className="flex items-center gap-2.5">
                          <span className="grid size-8 shrink-0 place-items-center rounded-full bg-secondary text-muted-foreground">
                            {user.username.slice(0, 1).toLocaleUpperCase('tr')}
                          </span>
                          <button
                            data-tour="kullanici-adi"
                            type="button"
                            title="Kullanıcı aktivitesini görüntüle"
                            className="hover:underline"
                            onClick={() => setActivityTarget(user)}
                          >
                            {user.username}
                          </button>
                          {isSelf && (
                            <span className="text-xs text-muted-foreground">(siz)</span>
                          )}
                        </div>
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
                          <SelectTrigger className="h-8 w-36 rounded-full">
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
                        <Badge variant={user.enabled ? 'success' : 'destructive'}>
                          {user.enabled ? 'Aktif' : 'Kapalı'}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="flex justify-end gap-0.5 rounded-full bg-secondary/40 p-0.5">
                          <Button
                            variant="ghost"
                            size="icon"
                            className="rounded-full"
                            disabled={busy}
                            title="Şifre sıfırla"
                            onClick={() => setResetTarget(user)}
                          >
                            <KeyRoundIcon />
                          </Button>
                          <Button
                            variant="ghost"
                            size="icon"
                            className="rounded-full"
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

      <Sayfalama first={first} max={MAX} total={total} loading={loading} onSayfaDegis={sayfaDegis} />

      <CreateUserDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={() => void load(search, first)}
      />
      <ResetPasswordDialog user={resetTarget} onClose={() => setResetTarget(null)} />
      <UserActivityDialog user={activityTarget} onClose={() => setActivityTarget(null)} />

      <GuidedTour open={tur.open} onClose={tur.close} steps={ADMIN_USERS_TOUR_STEPS} />
    </div>
  )
}
