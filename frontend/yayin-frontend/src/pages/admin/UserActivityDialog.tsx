import { useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { adminAnalitikApi } from '@/api/endpoints'
import type {
  AdSayiDto,
  HedefIzlemeOzetiDto,
  KullaniciAktiviteDto,
  TopEtiketDto,
  UserDto,
} from '@/api/types'
import { Badge } from '@/components/ui/badge'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Loader2Icon } from 'lucide-react'
import { TUR_ETIKET, turVariant } from './AdminEtkinliklerPage'

function sureFormatla(ms: number): string {
  const dk = Math.round(ms / 60_000)
  if (dk < 60) return `${dk} dk`
  return `${Math.floor(dk / 60)} sa ${dk % 60} dk`
}

function SureListesi({ baslik, veri }: { baslik: string; veri: HedefIzlemeOzetiDto[] }) {
  return (
    <div className="rounded-lg border">
      <div className="border-b px-3 py-2 text-xs font-medium text-muted-foreground">{baslik}</div>
      {veri.length === 0 ? (
        <div className="px-3 py-4 text-center text-sm text-muted-foreground">Veri yok.</div>
      ) : (
        <ul className="divide-y">
          {veri.map((e) => (
            <li key={e.id} className="flex items-center justify-between px-3 py-2 text-sm">
              <span className="font-medium">{e.ad}</span>
              <span className="text-right text-muted-foreground">
                {sureFormatla(e.toplamSureMs)}
                <span className="ml-1 text-xs">({e.oturumSayisi} oturum)</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** {@code klipAlinanKanallar} (isim bazlı) ve {@code manuelKayitAlinanKanallar}/{@code geriSarilanKanallar} (id bazlı) için ortak liste — ikisi de yalnızca sayı taşıyor. */
function SayiListesi({
  baslik,
  veri,
  anahtar,
}: {
  baslik: string
  veri: (AdSayiDto | TopEtiketDto)[]
  anahtar: (e: AdSayiDto | TopEtiketDto, i: number) => string | number
}) {
  return (
    <div className="rounded-lg border">
      <div className="border-b px-3 py-2 text-xs font-medium text-muted-foreground">{baslik}</div>
      {veri.length === 0 ? (
        <div className="px-3 py-4 text-center text-sm text-muted-foreground">Veri yok.</div>
      ) : (
        <ul className="divide-y">
          {veri.map((e, i) => (
            <li key={anahtar(e, i)} className="flex items-center justify-between px-3 py-2 text-sm">
              <span className="font-medium">{e.ad}</span>
              <span className="text-muted-foreground">{e.sayi}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/**
 * Kullanıcı bazlı aktivite detayı — {@code AdminUsersPage}'de bir kullanıcı
 * adına tıklanınca açılır. Yeni bir izleme noktası gerekmedi: tamamı mevcut
 * {@code etkinlik_kayitlari} verisinden ({@code EtkinlikService}) türetiliyor.
 * "Son Etkinlikler" bölümü türe göre süzülmemiş — yukarıdaki kategorilerin
 * kapsamadığı her şey için bir yakalama ağı.
 */
export function UserActivityDialog({ user, onClose }: { user: UserDto | null; onClose: () => void }) {
  const [veri, setVeri] = useState<KullaniciAktiviteDto | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!user) {
      setVeri(null)
      setError(null)
      return
    }
    let cancelled = false
    setLoading(true)
    void adminAnalitikApi
      .kullaniciAktivitesi(user.id)
      .then((sonuc) => {
        if (!cancelled) setVeri(sonuc)
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof ApiError ? e.message : 'Aktivite alınamadı.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [user])

  return (
    <Dialog open={user !== null} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[85vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{user?.username} — aktivite</DialogTitle>
          <DialogDescription>
            {veri?.sonGiris
              ? `Son giriş: ${new Date(veri.sonGiris).toLocaleString('tr-TR')}`
              : 'Kullanıcı davranışı özeti'}
          </DialogDescription>
        </DialogHeader>

        {loading && (
          <div className="grid place-items-center py-8 text-muted-foreground">
            <Loader2Icon className="animate-spin" />
          </div>
        )}

        {error && <p className="text-sm text-destructive">{error}</p>}

        {!loading && !error && veri && (
          <div className="flex flex-col gap-4">
            <div className="grid grid-cols-3 gap-3">
              <div className="rounded-lg border p-3">
                <div className="text-xs text-muted-foreground">Yüklenen video</div>
                <div className="mt-1 text-xl font-semibold">{veri.videoYuklemeSayisi}</div>
              </div>
              <div className="rounded-lg border p-3">
                <div className="text-xs text-muted-foreground">Oluşturulan klip</div>
                <div className="mt-1 text-xl font-semibold">{veri.klipSayisi}</div>
              </div>
              <div className="rounded-lg border p-3">
                <div className="text-xs text-muted-foreground">Toplam izleme</div>
                <div className="mt-1 text-xl font-semibold">
                  {sureFormatla(veri.toplamIzlemeSuresiMs)}
                </div>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-3">
              <SureListesi baslik="En çok izlediği kanallar" veri={veri.izlenenKanallar} />
              <SureListesi baslik="En çok dinlediği radyolar" veri={veri.dinlenenRadyolar} />
            </div>

            <div className="grid grid-cols-3 gap-3">
              <SayiListesi
                baslik="Klip aldığı kanallar"
                veri={veri.klipAlinanKanallar}
                anahtar={(e) => e.ad}
              />
              <SayiListesi
                baslik="Manuel kayıt aldığı kanallar"
                veri={veri.manuelKayitAlinanKanallar}
                anahtar={(e, i) => ('id' in e ? e.id : i)}
              />
              <SayiListesi
                baslik="Geriye sardığı kanallar"
                veri={veri.geriSarilanKanallar}
                anahtar={(e, i) => ('id' in e ? e.id : i)}
              />
            </div>

            <div className="rounded-lg border">
              <div className="border-b px-3 py-2 text-xs font-medium text-muted-foreground">
                Son etkinlikler
              </div>
              {veri.sonEtkinlikler.length === 0 ? (
                <div className="px-3 py-4 text-center text-sm text-muted-foreground">Veri yok.</div>
              ) : (
                <ul className="divide-y">
                  {veri.sonEtkinlikler.map((kayit) => (
                    <li
                      key={kayit.id}
                      className="flex items-center justify-between gap-3 px-3 py-2 text-sm"
                    >
                      <div className="flex min-w-0 items-center gap-2">
                        <Badge variant={turVariant(kayit.tur)}>{TUR_ETIKET[kayit.tur]}</Badge>
                        {kayit.hedefAdi && (
                          <span className="truncate text-muted-foreground">{kayit.hedefAdi}</span>
                        )}
                      </div>
                      <span className="whitespace-nowrap text-xs text-muted-foreground">
                        {new Date(kayit.olusturmaZamani).toLocaleString('tr-TR')}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  )
}
