import { useEffect, useRef, useState } from 'react'
import { subtitlesApi } from '@/api/endpoints'
import type { SubtitleDto } from '@/api/types'
import type { CaptureHandle } from '@/components/HlsPlayer'
import { cn } from '@/lib/utils'

/**
 * Video üzerine altyazı bindirmesi.
 *
 * <h2>Neden `playingDate()` ile eşleştiriliyor</h2>
 * Canlı yayında izleyici <b>6-12 saniye geride</b>: HLS paketleme gecikmesi.
 * Altyazının <b>geldiği an</b> değil, <b>taşıdığı zaman damgası</b>
 * belirleyici. "Geldi, göster" mantığı altyazıyı izleyicinin gördüğü kareden
 * önce gösterirdi.
 *
 * <p>`playingDate()` karenin yayındaki gerçek anını veriyor (hls.js
 * `EXT-X-PROGRAM-DATE-TIME`'dan okuyor); eşleştirme iki mutlak zaman
 * üzerinden yapılıyor.
 *
 * <h2>İki kaynak</h2>
 * <ul>
 *   <li><b>WebSocket</b> — canlı akış. Altyazı üretilir üretilmez geliyor.</li>
 *   <li><b>REST</b> — yalnızca açılışta bir kez, geçmişi doldurmak için.
 *       WebSocket bağlanmadan önce üretilmiş altyazılar aksi halde
 *       görünmezdi.</li>
 * </ul>
 */
export function SubtitleOverlay({
  channelId,
  capture,
  language,
  className,
}: {
  channelId: string
  capture: { current: CaptureHandle | null }
  /** Gösterilecek dil kodu — `en` her zaman var, diğerleri çeviri. */
  language: string
  className?: string
}) {
  const [current, setCurrent] = useState<SubtitleDto | null>(null)
  const cacheRef = useRef<SubtitleDto[]>([])

  // --- Geçmiş: açılışta bir kez ---
  useEffect(() => {
    cacheRef.current = []
    setCurrent(null)

    let cancelled = false
    void (async () => {
      try {
        const now = Date.now()
        const gelen = await subtitlesApi.list(
          channelId,
          new Date(now - BACKFILL_MS),
          new Date(now + BACKFILL_MS),
        )
        if (!cancelled) {
          cacheRef.current = ekle(cacheRef.current, gelen)
        }
      } catch {
        // Sessiz: canli akis WebSocket'ten geliyor ve gecmis ikincil.
        // Her hatada bildirim gostermek yayin izlemeyi bogardi.
      }
    })()
    return () => {
      cancelled = true
    }
  }, [channelId])

  // --- Canlı: WebSocket ---
  useEffect(() => {
    let socket: WebSocket | null = null
    let retry: number | null = null
    let bekleme = 1000
    let kapandi = false

    const bagla = () => {
      if (kapandi) return
      // Aynı origin: nginx /ws/ yolunu backend'e vekilliyor. Mutlak adres
      // yazılsaydı alan adı ya da port değiştiğinde kırılırdı.
      const proto = location.protocol === 'https:' ? 'wss:' : 'ws:'
      socket = new WebSocket(`${proto}//${location.host}/ws/altyazi/${channelId}`)

      socket.onopen = () => {
        bekleme = 1000
      }
      socket.onmessage = (e) => {
        try {
          const gelen = JSON.parse(e.data) as SubtitleDto
          cacheRef.current = ekle(cacheRef.current, [gelen])
        } catch {
          // Bozuk mesaj akisi durdurmamali.
        }
      }
      socket.onclose = () => {
        if (kapandi) return
        // Ustel geri cekilme: sunucu yeniden baslarken saniyede bir
        // baglanmaya calismak bosuna yuk.
        retry = window.setTimeout(bagla, bekleme)
        bekleme = Math.min(bekleme * 2, 30_000)
      }
    }

    bagla()
    return () => {
      kapandi = true
      if (retry) clearTimeout(retry)
      socket?.close()
    }
  }, [channelId])

  // --- Eşleştirme ---
  useEffect(() => {
    const tick = () => {
      const handle = capture.current
      if (!handle) return
      const now = handle.playingDate().getTime()

      // Su ana denk gelen bolutu sec. Birden fazla varsa EN SON baslayani:
      // ortusen bolutlerde (zorla kesim sonrasi) yeni olan dogru.
      const eslesen = cacheRef.current
        .filter((s) => Date.parse(s.baslangic) <= now && Date.parse(s.bitis) > now)
        .sort((a, b) => Date.parse(b.baslangic) - Date.parse(a.baslangic))[0]

      setCurrent(eslesen ?? null)

      // Onbellegi budama: sinirsiz buyurse saatler sonra binlerce kayit
      // her tikte suzulurdu.
      if (cacheRef.current.length > CACHE_LIMIT) {
        cacheRef.current = cacheRef.current.filter(
          (s) => Date.parse(s.bitis) > now - BACKFILL_MS,
        )
      }
    }

    tick()
    const timer = setInterval(tick, TICK_MS)
    return () => clearInterval(timer)
  }, [capture])

  const metin = current?.metinler?.[language]
  if (!metin) {
    return null
  }

  return (
    <div
      className={cn(
        'pointer-events-none absolute inset-x-0 bottom-0 flex justify-center p-3',
        className,
      )}
    >
      <p
        // Arka plan sart: acik sahnelerde beyaz yazi okunmuyor. Golge tek
        // basina yetmiyor, hareketli goruntude titriyor.
        className="max-w-[90%] rounded-md bg-black/70 px-3 py-1.5 text-center text-sm
                   leading-snug text-white shadow-lg"
      >
        {metin}
      </p>
    </div>
  )
}

/** Aynı bölüt iki yoldan gelebiliyor (REST + WebSocket); kimlikle tekilleniyor. */
function ekle(mevcut: SubtitleDto[], gelen: SubtitleDto[]): SubtitleDto[] {
  const harita = new Map(mevcut.map((s) => [anahtar(s), s]))
  for (const s of gelen) {
    harita.set(anahtar(s), s)
  }
  return [...harita.values()]
}

/** WebSocket olayında `id` yok; kanal + başlangıç zaten tekil. */
function anahtar(s: SubtitleDto): string {
  return s.id ?? s.baslangic
}

/** Eşleştirme sıklığı. Altyazı bölütleri saniyeler sürdüğü için 250 ms yeterli. */
const TICK_MS = 250

/** Açılışta doldurulan geçmiş penceresi. */
const BACKFILL_MS = 60_000

/** Önbellek bu sayıyı aşınca eski kayıtlar budanıyor. */
const CACHE_LIMIT = 200

/**
 * Seçilebilir altyazı dilleri.
 *
 * `en` pivot ve her zaman var; diğerleri ondan çevriliyor. Kaynak dilde
 * altyazı yok — Whisper `task=translate` ile çalışıyor.
 */
export const SUBTITLE_LANGS = [
  { kod: 'kapali', ad: 'Altyazı yok' },
  { kod: 'tr', ad: 'Türkçe' },
  { kod: 'en', ad: 'English' },
  { kod: 'de', ad: 'Deutsch' },
  { kod: 'ru', ad: 'Русский' },
] as const
