import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { AlertTriangleIcon, Loader2Icon } from 'lucide-react'
import { channelsApi } from '@/api/endpoints'
import { ApiError } from '@/api/client'
import type { ChannelDeletionSummary, ChannelDto } from '@/api/types'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

/**
 * Kanal silme onayı.
 *
 * <h2>Neden {@code confirm()} yetmedi</h2>
 * Tarayıcının onay kutusu tek bir soru sorabiliyor ve <b>hiçbir bilgi</b>
 * taşımıyordu: kullanıcı neyin gideceğini görmeden "Tamam"a basıyordu. 3
 * klip ile 300 klip aynı karar değil.
 *
 * <p>Ayrıca iki şey daha gerekiyordu ve ikisi de {@code confirm()} ile
 * mümkün değil: içeriğin korunup korunmayacağı seçimi ve şifre.
 *
 * <h2>Neden şifre</h2>
 * Silme geri alınamaz ve tek tıkla ulaşılabilir bir yerde duruyor. Rol
 * kontrolü "bu kişi silebilir mi" sorusunu cevaplıyor; şifre "şu an gerçekten
 * bu kişi mi ve bilerek mi" sorusunu. Açık kalmış bir oturumda yanlışlıkla
 * silinen bir kanalın 7 günlük kaydı geri gelmiyor.
 */
export function DeleteChannelDialog({
  channel,
  onClose,
  onDeleted,
}: {
  /** Silinecek kanal; null ise iletişim kutusu kapalı. */
  channel: ChannelDto | null
  onClose: () => void
  onDeleted: (channelId: string) => void
}) {
  const [ozet, setOzet] = useState<ChannelDeletionSummary | null>(null)
  const [yukleniyor, setYukleniyor] = useState(false)
  const [sifre, setSifre] = useState('')
  /**
   * Klip ve ekran görüntüsü <b>ayrı ayrı</b> seçiliyor.
   *
   * <p>Önce tek bir "içeriği sil" kutusuydu ve kullanıcıyı olmayan bir
   * tercihe zorluyordu: klip emek harcanmış bir çıktı, ekran görüntüsü tek
   * tıkla yeniden alınabilir. Birini tutup diğerini atmak meşru bir istek.
   *
   * <p><b>İkisi de varsayılan olarak KAPALI</b> — silme geri alınamaz ve
   * varsayılanın güvenli tarafta olması gerekiyor.
   */
  const [klipSilinsin, setKlipSilinsin] = useState(false)
  const [ekranSilinsin, setEkranSilinsin] = useState(false)
  /**
   * DVR nesneleri hemen silinsin mi.
   *
   * <p><b>Diğer ikisinden farklı anlama geliyor.</b> Zaman çizelgesi kanala
   * bağlı ve her koşulda gidiyor; korunan şey yalnızca MinIO'daki baytlar.
   * Değeri bir yanlış tıklama ağı olması — kayıt birkaç gün daha kovada
   * duruyor ve saklama kuralı dolduğunda kendiliğinden temizleniyor.
   */
  const [dvrSilinsin, setDvrSilinsin] = useState(false)
  const [gonderiliyor, setGonderiliyor] = useState(false)

  useEffect(() => {
    if (!channel) return
    // Her acilista sifirla: onceki kanalin ozeti ve girilen sifre kalirsa
    // kullanici yanlis sayilara bakarak onay verebilirdi.
    setOzet(null)
    setSifre('')
    setKlipSilinsin(false)
    setEkranSilinsin(false)
    setDvrSilinsin(false)
    setYukleniyor(true)

    let iptal = false
    void (async () => {
      try {
        const gelen = await channelsApi.deletionSummary(channel.id)
        if (!iptal) setOzet(gelen)
      } catch (e) {
        if (!iptal) {
          toast.error(e instanceof ApiError ? e.message : 'Silme özeti alınamadı.')
          onClose()
        }
      } finally {
        if (!iptal) setYukleniyor(false)
      }
    })()
    return () => {
      iptal = true
    }
  }, [channel, onClose])

  async function sil() {
    if (!channel) return
    setGonderiliyor(true)
    try {
      await channelsApi.remove(channel.id, sifre, {
        deleteClips: klipSilinsin,
        deleteScreenshots: ekranSilinsin,
        deleteDvr: dvrSilinsin,
      })
      onDeleted(channel.id)
      toast.success(`${channel.name} silindi.`, { description: sonucMetni() })
      onClose()
    } catch (e) {
      // Sifre hatasi en olasi durum ve iletisim kutusu ACIK kalmali ki
      // kullanici yeniden denesin; kapansaydi ozeti bastan yuklerdi.
      toast.error(e instanceof ApiError ? e.message : 'Kanal silinemedi.')
    } finally {
      setGonderiliyor(false)
    }
  }

  /** Bildirimde ne olduğunu tek cümleyle söylüyor. */
  function sonucMetni(): string {
    const silinen: string[] = []
    const korunan: string[] = []
    if ((ozet?.clipCount ?? 0) > 0) (klipSilinsin ? silinen : korunan).push('klipler')
    if ((ozet?.screenshotCount ?? 0) > 0) {
      (ekranSilinsin ? silinen : korunan).push('ekran görüntüleri')
    }
    const parcalar: string[] = []
    if (silinen.length) parcalar.push(`${silinen.join(' ve ')} silindi`)
    if (korunan.length) parcalar.push(`${korunan.join(' ve ')} korundu`)
    if ((ozet?.dvrSegmentCount ?? 0) > 0) {
      parcalar.push(dvrSilinsin ? 'DVR dosyaları silindi' : 'DVR dosyaları bırakıldı')
    }
    return parcalar.length ? `${parcalar.join(', ')}.` : 'Kanal silindi.'
  }

  return (
    <Dialog open={channel !== null} onOpenChange={(acik) => !acik && onClose()}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Kanalı sil</DialogTitle>
          <DialogDescription>
            <strong className="text-foreground">{channel?.name}</strong> silinecek ve
            MediaMTX'teki yayın durdurulacak. Bu işlem geri alınamaz.
          </DialogDescription>
        </DialogHeader>

        <DialogBody className="space-y-4 py-1">
          {yukleniyor && (
            <p className="flex items-center gap-2 py-4 text-sm text-muted-foreground">
              <Loader2Icon className="size-4 animate-spin" />
              İçerik taranıyor…
            </p>
          )}

          {ozet && (
            <>
              {ozet.streaming && (
                <p className="flex items-start gap-2 rounded-lg bg-status-warning-bg p-3 text-sm text-status-warning">
                  <AlertTriangleIcon className="mt-0.5 size-4 shrink-0" />
                  Bu kanal <strong>şu anda yayında</strong>. Silmek yayını anında keser.
                </p>
              )}

              <div className="rounded-xl border">
                <SecimSatiri
                  ad="DVR kaydı"
                  deger={
                    ozet.dvrSegmentCount === 0
                      ? 'yok'
                      : `${sureBicimi(ozet.dvrHours)} · ${boyut(ozet.dvrBytes)}`
                  }
                  varMi={ozet.dvrSegmentCount > 0}
                  secili={dvrSilinsin}
                  onChange={setDvrSilinsin}
                  // DVR'da "sakla" diğer ikisinden farklı ve bu gizlenmiyor:
                  // çizelge her koşulda gidiyor, korunan yalnızca baytlar.
                  korunanMetni="dosyalar kalır"
                />

                <SecimSatiri
                  ad="Klipler"
                  deger={
                    ozet.clipCount === 0
                      ? 'yok'
                      : `${ozet.clipCount} adet · ${boyut(ozet.clipBytes)}`
                  }
                  varMi={ozet.clipCount > 0}
                  secili={klipSilinsin}
                  onChange={setKlipSilinsin}
                />
                <SecimSatiri
                  ad="Ekran görüntüleri"
                  deger={ozet.screenshotCount === 0 ? 'yok' : `${ozet.screenshotCount} adet`}
                  varMi={ozet.screenshotCount > 0}
                  secili={ekranSilinsin}
                  onChange={setEkranSilinsin}
                  son
                />
              </div>

              {(ozet.clipCount > 0 || ozet.screenshotCount > 0) && (
                <p className="text-sm text-muted-foreground">
                  İşaretlenmeyen klip ve ekran görüntüleri korunur; listede{' '}
                  <em className="text-foreground">{channel?.name} (silinmiş)</em> olarak
                  görünürler.
                </p>
              )}

              {ozet.dvrSegmentCount > 0 && !dvrSilinsin && (
                <p className="text-sm text-muted-foreground">
                  <strong className="text-foreground">DVR farklı:</strong> geriye
                  sarma çizelgesi her koşulda siliniyor — kanalı olmayan bir kaydın
                  gösterileceği yer yok. İşaretlenmezse yalnızca dosyalar MinIO'da
                  kalır ve saklama süresi dolunca kendiliğinden temizlenir.
                </p>
              )}

              <div className="space-y-2">
                <Label htmlFor="silme-sifre">Devam etmek için şifrenizi girin</Label>
                <Input
                  id="silme-sifre"
                  type="password"
                  autoComplete="current-password"
                  value={sifre}
                  onChange={(e) => setSifre(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && sifre) void sil()
                  }}
                  placeholder="Kendi şifreniz"
                />
              </div>
            </>
          )}
        </DialogBody>

        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={gonderiliyor}>
            Vazgeç
          </Button>
          <Button
            variant="destructive"
            disabled={!ozet || !sifre || gonderiliyor}
            onClick={() => void sil()}
          >
            {gonderiliyor && <Loader2Icon className="animate-spin" />}
            Kalıcı olarak sil
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

/**
 * Onay kutusu taşıyan döküm satırı.
 *
 * <p>Kutu ve sayı <b>aynı satırda</b>: "12 klip var" ile "silinsin mi"
 * ayrı yerlerde dururken kullanıcı hangi sayının hangi kutuya ait olduğunu
 * eşleştirmek zorunda kalıyordu.
 *
 * <p>İçerik yoksa kutu <b>hiç çizilmiyor</b> — sıfır klibi silmeyi seçmek
 * anlamsız ve seçenek sunmak "bir şey kaçırıyor muyum" hissi veriyor.
 */
function SecimSatiri({
  ad,
  deger,
  varMi,
  secili,
  onChange,
  korunanMetni = 'korunacak',
  son = false,
}: {
  ad: string
  deger: string
  varMi: boolean
  secili: boolean
  onChange: (v: boolean) => void
  /** İşaretsizken gösterilen rozet. DVR'da "korunacak" yanıltıcı olurdu. */
  korunanMetni?: string
  son?: boolean
}) {
  if (!varMi) {
    return <Satir ad={ad} deger={deger} son={son} />
  }
  return (
    <label
      className={cn(
        'flex cursor-pointer items-center justify-between gap-3 px-3.5 py-3 text-sm transition-colors hover:bg-accent/50',
        !son && 'border-b',
      )}
    >
      <span className="flex items-center gap-3">
        <input
          type="checkbox"
          checked={secili}
          onChange={(e) => onChange(e.target.checked)}
          className="size-4 accent-[var(--destructive)]"
        />
        <span>
          <span className="block font-medium">{ad}</span>
          <span className="block text-xs text-muted-foreground">{deger}</span>
        </span>
      </span>
      <span
        className={cn(
          'shrink-0 rounded-full px-2 py-0.5 text-[11px] font-medium',
          secili
            ? 'bg-status-error-bg text-status-error'
            : 'bg-secondary text-muted-foreground',
        )}
      >
        {secili ? 'silinecek' : korunanMetni}
      </span>
    </label>
  )
}

function Satir({
  ad,
  deger,
  not,
  vurgu = false,
  son = false,
}: {
  ad: string
  deger: string
  not?: string
  vurgu?: boolean
  son?: boolean
}) {
  return (
    <div
      className={cn(
        'flex items-center justify-between gap-3 px-3.5 py-2.5 text-sm',
        !son && 'border-b',
      )}
    >
      <span className="text-muted-foreground">{ad}</span>
      <span className="flex items-center gap-2">
        <span>{deger}</span>
        {not && (
          <span
            className={cn(
              'rounded-full px-2 py-0.5 text-[11px] font-medium',
              vurgu
                ? 'bg-status-error-bg text-status-error'
                : 'bg-secondary text-muted-foreground',
            )}
          >
            {not}
          </span>
        )}
      </span>
    </div>
  )
}

/** Saat cinsinden süreyi okunur hale getirir. */
function sureBicimi(saat: number): string {
  if (saat < 1) return `${Math.round(saat * 60)} dk`
  return `${saat.toFixed(1)} saat`
}

function boyut(bayt: number): string {
  if (bayt >= 1024 ** 3) return `${(bayt / 1024 ** 3).toFixed(1)} GB`
  if (bayt >= 1024 ** 2) return `${Math.round(bayt / 1024 ** 2)} MB`
  return `${Math.round(bayt / 1024)} KB`
}
