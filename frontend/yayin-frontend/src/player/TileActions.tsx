import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { clipsApi, recordingsApi, screenshotsApi } from '@/api/endpoints'
import type { ActiveRecordingDto, ChannelDto } from '@/api/types'
import type { CaptureHandle } from '@/components/HlsPlayer'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { CameraIcon, CircleIcon, Loader2Icon, SquareIcon } from 'lucide-react'

/** JPEG kalitesi: 0.92 gözle kayıpsız sayılır, PNG'nin üçte biri yer kaplar. */
const JPEG_QUALITY = 0.92

function gecenSure(startedAt: string): string {
  const s = Math.max(0, Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000))
  const m = Math.floor(s / 60)
  return `${m}:${String(s % 60).padStart(2, '0')}`
}

/**
 * Kaydın süresi — bitiş anı SUNUCUDAN geliyor.
 *
 * <p>{@link gecenSure} kullanılamaz: o, tarayıcı saatine göre "şimdi"ye kadar
 * geçeni ölçüyor. Klibin aralığı sunucuda belirleniyor ve iki saat birkaç
 * saniye kayabilir; kullanıcıya gerçekte kaydedilen aralık söylenmeli.
 */
function sureMetni(start: string, end: string): string {
  const s = Math.max(0, Math.round((new Date(end).getTime() - new Date(start).getTime()) / 1000))
  if (s < 60) {
    return `${s} saniye`
  }
  const dk = Math.floor(s / 60)
  const sn = s % 60
  return sn === 0 ? `${dk} dakika` : `${dk} dk ${sn} sn`
}

/**
 * Karo üzerindeki kayıt ve kare yakalama düğmeleri.
 *
 * <p><b>Kare tarayıcıda yakalanıyor.</b> Canvas'a çizilen görüntü kullanıcının
 * gördüğü karenin ta kendisi; sunucudan yakalansaydı HLS gecikmesi yüzünden
 * 6-20 saniye ilerideki bir kare gelirdi. Video MSE ile beslendiği için
 * canvas "tainted" olmuyor, {@code toBlob} çalışıyor.
 */
export function TileActions({
  channel,
  capture,
  recording,
  rewound,
  pendingClipStart,
  onPendingClipStartChange,
  onRecordingChanged,
}: {
  channel: ChannelDto
  capture: { current: CaptureHandle | null }
  /** Bu kanalda devam eden kayıt; yoksa null. */
  recording: ActiveRecordingDto | null
  /** Oynatıcı şu an geri sarılmış bir DVR bölümünü mü gösteriyor. */
  rewound: boolean
  /**
   * Geri sarılmışken "kayda başla"nın işaretlediği GEÇMİŞ an — PersistentPlayers'ta
   * tutuluyor (bu bileşende DEĞİL) çünkü geri sarılan bölüm kendiliğinden
   * bitip canlıya dönerse (bkz. backToLive) klibin de o an otomatik
   * bitirilmesi gerekiyor; o karar burada değil üst bileşende veriliyor.
   *
   * <p>Sunucuda bir kayıt AÇILMIYOR — {@code recordingsApi.start/stop} hep
   * "şimdi"den başlar, geçmiş bir andan başlamayı desteklemiyor. Bunun yerine
   * başlangıç anı istemcide hatırlanıyor; "durdur"a basıldığında bu andan
   * ŞİMDİYE kadar DVR'dan doğrudan bir klip istenip aynı sonuca ulaşılıyor —
   * DVR zaten sürekli kaydettiği için bu geriye dönük istek her zaman
   * karşılanabilir.
   */
  pendingClipStart: Date | null
  onPendingClipStartChange: (an: Date | null) => void
  onRecordingChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [, tick] = useState(0)
  const shotBusy = useRef(false)

  // Kayıt/klip işaretleme sürerken saniye sayacını ilerlet. Süre istemcide
  // hesaplanıyor; sunucudan saniye saniye çekmek gereksiz trafik olurdu.
  useEffect(() => {
    if (!recording && !pendingClipStart) return
    const timer = setInterval(() => tick((n) => n + 1), 1000)
    return () => clearInterval(timer)
  }, [recording, pendingClipStart])

  async function toggleRecording() {
    setBusy(true)
    try {
      if (pendingClipStart) {
        // Geri sarılmışken işaretlenmiş klip — bitiş de "şimdi" (new Date())
        // DEĞİL, "durdur"a basıldığı an EKRANDA GÖSTERİLEN zaman: kullanıcı
        // hâlâ geri sarılmış haldeyken durdurursa gerçek şimdi çok daha
        // ileride olur ve rewind noktasından şimdiye kadarki KOCAMAN bir
        // aralık klibe girerdi (gerçek bug, düzeltildi). Başlangıçla
        // simetrik: ikisi de capture.current.playingDate()'ten geliyor —
        // canlıya dönülmüşse bu zaten gerçek "şimdi"ye (HLS gecikmesi kadar)
        // yakın çıkıyor, hâlâ geri sarılmışsa GÖRÜNEN anı veriyor.
        // recordingsApi'ye hiç uğramıyoruz (bkz. yukarıdaki pendingClipStart
        // javadoc'u). Önce temizle: istek başarısız olsa bile düğme
        // "sonsuza dek kaydediyor" görünümünde takılı kalmasın.
        const start = pendingClipStart
        const end = capture.current?.playingDate() ?? new Date()
        onPendingClipStartChange(null)
        await clipsApi.create(channel.id, {
          start: start.toISOString(),
          end: end.toISOString(),
        })
        toast.success('Klip kuyruğa alındı.', {
          description: 'Hazır olunca Klipler sayfasından indirebilirsiniz.',
        })
      } else if (recording) {
        const sonuc = await recordingsApi.stop(channel.id)
        // Durdurma her koşulda başarılı; klip AYRI bir iş ve açılamayabilir
        // (örneğin aralığın tamamı kayıtlı değilse). Her iki durumda da
        // "hazırlanıyor" demek, gelmeyecek bir klip vaat etmek olurdu.
        if (sonuc.clip) {
          toast.success('Kayıt durduruldu, klip hazırlanıyor.', {
            description: `${sureMetni(sonuc.start, sonuc.end)} · hazır olunca Klipler sayfasında.`,
          })
        } else {
          toast.warning('Kayıt durduruldu ama klip açılamadı.', {
            description: sonuc.error ?? undefined,
          })
        }
      } else if (rewound) {
        // Geri sarılmışken "kayda başla": canlıdan değil, İZLEDİĞİNİZ
        // geçmiş andan başlasın istendiği için (17 Ağustos, gerçek istek)
        // sunucuda kayıt açmıyoruz — bkz. pendingClipStart javadoc'u.
        const an = capture.current?.playingDate() ?? null
        if (!an) {
          toast.error('Hangi andan başlanacağı belirlenemedi.')
        } else {
          onPendingClipStartChange(an)
          toast.success(
            `${channel.name} — ${an.toLocaleTimeString('tr-TR')} anından itibaren klip işaretlendi.`,
            { description: 'Bitirmek için tekrar basın.' },
          )
        }
      } else {
        await recordingsApi.start(channel.id)
        toast.success(`${channel.name} kaydediliyor.`)
      }
      onRecordingChanged()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Kayıt işlemi başarısız.')
      // Hata durumunda kayıt listesini tazele: "zaten kayıt var" hatası
      // alındıysa recording prop hâlâ null olabilir (liste eski) ve düğme
      // yanlış durumda kalır. Sayfa değişip geri dönüldüğünde de aynı
      // durum oluşur — periyodik yoklama olsa da anında düzelmeyebilir.
      onRecordingChanged()
    } finally {
      setBusy(false)
    }
  }

  async function captureFrame() {
    const handle = capture.current
    const video = handle?.video
    if (!video || !video.videoWidth) {
      toast.error('Görüntü henüz hazır değil.')
      return
    }
    if (shotBusy.current) return
    shotBusy.current = true

    try {
      const canvas = document.createElement('canvas')
      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      const ctx = canvas.getContext('2d')
      if (!ctx) throw new Error('Canvas oluşturulamadı.')
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height)

      const blob = await new Promise<Blob | null>((resolve) =>
        canvas.toBlob(resolve, 'image/jpeg', JPEG_QUALITY),
      )
      if (!blob) throw new Error('Kare kodlanamadı.')

      await screenshotsApi.capture(
        channel.id, blob, handle.playingDate(), canvas.width, canvas.height,
      )
      toast.success('Kare galeriye eklendi.')
    } catch (e) {
      toast.error(e instanceof ApiError || e instanceof Error ? e.message : 'Kare alınamadı.')
    } finally {
      shotBusy.current = false
    }
  }

  const kaydediyor = recording != null || pendingClipStart != null

  return (
    <div className="pointer-events-auto flex items-center gap-1">
      {kaydediyor && (
        <span className="flex items-center gap-1 rounded-full bg-status-live-bg px-2 py-0.5 text-xs font-medium text-status-live">
          <span className="size-1.5 animate-pulse rounded-full bg-status-live" />
          {gecenSure(recording ? recording.startedAt : pendingClipStart!.toISOString())}
        </span>
      )}

      <Button
        variant="secondary"
        size="icon"
        className={cn('size-7', kaydediyor && 'text-status-live')}
        disabled={busy}
        title={
          recording
            ? 'Kaydı durdur'
            : pendingClipStart
              ? 'Klibi bitir'
              : rewound
                ? 'Bu andan klip almaya başla'
                : 'Kayda başla'
        }
        onClick={(e) => {
          e.stopPropagation()
          void toggleRecording()
        }}
      >
        {busy ? <Loader2Icon className="animate-spin" /> : kaydediyor ? <SquareIcon /> : <CircleIcon />}
      </Button>

      {/* Dar yerleşimde de gösteriliyor: kare yakalama küçük döşeme
          görünümünde tam da istenen şey ve iki düğme yan yana sığıyor. */}
      <Button
        variant="secondary"
        size="icon"
        className="size-7"
        title="Kare yakala"
        onClick={(e) => {
          e.stopPropagation()
          void captureFrame()
        }}
      >
        <CameraIcon />
      </Button>
    </div>
  )
}
