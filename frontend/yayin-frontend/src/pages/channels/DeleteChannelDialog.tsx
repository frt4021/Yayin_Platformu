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
  const [icerikSilinsin, setIcerikSilinsin] = useState(false)
  const [gonderiliyor, setGonderiliyor] = useState(false)

  useEffect(() => {
    if (!channel) return
    // Her acilista sifirla: onceki kanalin ozeti ve girilen sifre kalirsa
    // kullanici yanlis sayilara bakarak onay verebilirdi.
    setOzet(null)
    setSifre('')
    setIcerikSilinsin(false)
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
      await channelsApi.remove(channel.id, sifre, icerikSilinsin)
      onDeleted(channel.id)
      toast.success(`${channel.name} silindi.`, {
        description: icerikSilinsin
          ? 'Klip ve ekran görüntüleri de silindi.'
          : 'Klip ve ekran görüntüleri korundu.',
      })
      onClose()
    } catch (e) {
      // Sifre hatasi en olasi durum ve iletisim kutusu ACIK kalmali ki
      // kullanici yeniden denesin; kapansaydi ozeti bastan yuklerdi.
      toast.error(e instanceof ApiError ? e.message : 'Kanal silinemedi.')
    } finally {
      setGonderiliyor(false)
    }
  }

  const icerikVar = (ozet?.clipCount ?? 0) + (ozet?.screenshotCount ?? 0) > 0

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
                <Satir
                  ad="DVR kaydı"
                  deger={
                    ozet.dvrSegmentCount === 0
                      ? 'yok'
                      : `${sureBicimi(ozet.dvrHours)} · ${boyut(ozet.dvrBytes)}`
                  }
                  // DVR her kosulda gidiyor; secenegi yokmus gibi degil,
                  // ACIKCA "her zaman silinir" diye yaziliyor.
                  not="her zaman silinir"
                  vurgu
                />
                <Satir
                  ad="Klipler"
                  deger={ozet.clipCount === 0 ? 'yok' : `${ozet.clipCount} adet · ${boyut(ozet.clipBytes)}`}
                  not={ozet.clipCount === 0 ? undefined : icerikSilinsin ? 'silinecek' : 'korunacak'}
                  vurgu={icerikSilinsin && ozet.clipCount > 0}
                />
                <Satir
                  ad="Ekran görüntüleri"
                  deger={ozet.screenshotCount === 0 ? 'yok' : `${ozet.screenshotCount} adet`}
                  not={
                    ozet.screenshotCount === 0 ? undefined : icerikSilinsin ? 'silinecek' : 'korunacak'
                  }
                  vurgu={icerikSilinsin && ozet.screenshotCount > 0}
                  son
                />
              </div>

              {icerikVar && (
                <label className="flex cursor-pointer items-start gap-3 rounded-xl border p-3 transition-colors hover:bg-accent/50">
                  <input
                    type="checkbox"
                    checked={icerikSilinsin}
                    onChange={(e) => setIcerikSilinsin(e.target.checked)}
                    className="mt-0.5 size-4 accent-[var(--destructive)]"
                  />
                  <span className="text-sm">
                    <span className="font-medium">Klip ve ekran görüntüleri de silinsin</span>
                    <span className="mt-0.5 block text-muted-foreground">
                      İşaretlenmezse dosyalar korunur; listede{' '}
                      <em>{channel?.name} (silinmiş)</em> olarak görünürler.
                    </span>
                  </span>
                </label>
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
