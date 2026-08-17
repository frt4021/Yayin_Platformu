import { useEffect, useRef, useState } from 'react'
import { subtitlesApi } from '@/api/endpoints'
import type { SubtitleDto } from '@/api/types'
import type { CaptureHandle } from '@/components/HlsPlayer'
import { cn } from '@/lib/utils'
import { altyaziDilleriOku } from './oynaticiAyarlari'

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
  /**
   * Ekranda o an duran metin.
   *
   * <p>Bölütün kendisi değil <b>parçası</b> tutuluyor: uzun bölütler okuma
   * hızına sığmadığı için parçalanıp sırayla gösteriliyor (bkz. {@link parcala}).
   */
  const [gosterilen, setGosterilen] = useState<string | null>(null)
  const cacheRef = useRef<SubtitleDto[]>([])

  /** Bir dakikalık HLS gecikmesi ölçümleri; dolduğunda ortancası gönderiliyor. */
  const gecikmeler = useRef<number[]>([])

  // --- Geçmiş: açılışta bir kez ---
  useEffect(() => {
    cacheRef.current = []
    setGosterilen(null)

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

      setGosterilen(eslesen ? parcaSec(eslesen, language, now) : null)

      // HLS gecikmesi: izleyicinin canli kenardan ne kadar geride oldugu.
      // Sunucu bunu BILEMEZ (tampona ve aga bagli) ve bugune kadar
      // varsayiliyordu; kapsama karari boylece olculmus bir sayi ile tahmin
      // edilmis bir sayinin karsilastirmasiydi.
      gecikmeler.current.push(Date.now() - now)

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
    // language de bagimlilikta: dil degisince gosterilen parca da degismeli.
  }, [capture, language])

  // --- HLS gecikmesini bildir ---
  //
  // DAKIKADA BIR, her karede degil: eslestirme 250 ms'de bir calisiyor ve o
  // siklikta istek atmak izleyici basina saniyede dort istek ederdi.
  //
  // Ortanca gonderiliyor, ortalama degil: tampon doldururken ya da atlama
  // aninda birkac uc deger cikiyor ve ortalamayi kaydiriyor.
  useEffect(() => {
    const timer = setInterval(() => {
      const olcumler = gecikmeler.current
      gecikmeler.current = []
      if (olcumler.length === 0) return

      const sirali = [...olcumler].sort((a, b) => a - b)
      const ortanca = sirali[Math.floor(sirali.length / 2)]

      void subtitlesApi.hlsGecikmeBildir(channelId, Math.round(ortanca)).catch(() => {
        // Sessiz: olcum altyazinin yan urunu, basarisiz olmasi izlemeyi
        // etkilemiyor. Hata gostermek gurultu olurdu.
      })
    }, BILDIRIM_MS)
    return () => clearInterval(timer)
  }, [channelId])

  if (!gosterilen) {
    return null
  }

  return (
    // container-type: inline-size -- icerideki punto KARO GENISLIGINE gore
    // olcekleniyor. Sabit punto, 4x4 mozaikte kocaman, tam ekranda minicik
    // kaliyordu; ikisi de okunmuyordu.
    <div
      className={cn(
        'pointer-events-none absolute inset-x-0 bottom-0 flex justify-center p-3 [container-type:inline-size]',
        className,
      )}
    >
      <p
        // Arka plan sart: acik sahnelerde beyaz yazi okunmuyor. Golge tek
        // basina yetmiyor, hareketli goruntude titriyor -- ikisi birlikte.
        className="rounded-md bg-black/70 px-3 py-1.5 text-center text-white"
        style={{
          // Yayin altyazisi standartlarindan:
          //
          // FONT -- genis ve yuksek x-boyu olan humanist sans. Arial ve
          // Verdana bu isin klasikleri (Netflix Arial kullaniyor); dar ya
          // da serif yuzler hareketli goruntude okunurlugu dusuruyor.
          fontFamily: '"Segoe UI", Verdana, Arial, Helvetica, sans-serif',

          // PUNTO -- karo genisliginin ~%3'u, alt ve ust sinirla. cqw
          // birimi kapsayicinin genisligine gore olceklendigi icin ayni
          // deger hem mozaik karosunda hem tam ekranda dogru cikiyor.
          fontSize: 'clamp(13px, 3cqw, 30px)',

          // SATIR YUKSEKLIGI -- 1,25. Daha siki olursa iki satirli
          // altyazida satirlar birbirine giriyor.
          lineHeight: 1.25,

          // SATIR UZUNLUGU -- en fazla ~38 karakter. Standartlar 37-42
          // arasini oneriyor; uzun satirda goz satir sonunu bulamiyor.
          // ASIL SORUN BUYDU: sinir yoktu ve uzun cumleler tek satira
          // yayilip ekrani bastan basa kapliyordu.
          maxWidth: 'min(38ch, 90%)',

          // Kelimeleri iki satira DENGELI dagitiyor: 8 kelime + 1 kelime
          // yerine 5 + 4. Desteklemeyen tarayicida sessizce yok sayiliyor.
          textWrap: 'balance',

          // Kontur: koyu zeminli kutu acik sahnelerde bile yetmeyebiliyor.
          textShadow: '0 1px 2px rgba(0,0,0,0.9)',

          // Hafif kalin -- ince yazi hareketli goruntude titriyor.
          fontWeight: 500,
        }}
      >
        {gosterilen}
      </p>
    </div>
  )
}

/**
 * Bölütün o ana denk gelen parçasını verir.
 *
 * <h2>Neden parçalanıyor</h2>
 * Yayın altyazısı standartları okuma hızını <b>~17-20 karakter/saniye</b> ile
 * sınırlıyor. 6 saniyelik bir bölüt 180 karakter üretirse 30 kar/sn olur ve
 * izleyici cümleyi bitiremeden altyazı değişir — kullanıcının tarif ettiği
 * "hemen gidiyor, birbirini kaçırıyor" tam olarak bu.
 *
 * <p>Metin ekrana sığan parçalara bölünüyor ve bölütün süresi parçalara
 * <b>eşit</b> pay ediliyor. Hiçbir şey kaybolmuyor; kırpmanın aksine
 * cümlenin sonu da görünüyor.
 *
 * <p><b>Kısa altyazılarda hiçbir şey değişmiyor:</b> tek parçaya sığıyorsa
 * eskisi gibi bölüt boyunca sabit duruyor.
 *
 * <h2>Neden eşit pay</h2>
 * Parça başına gerçek okuma süresini karakter sayısıyla ağırlıklandırmak daha
 * doğru olurdu ama parçalar zaten yaklaşık eşit uzunlukta (bölme sınırı aynı)
 * ve ağırlıklandırma görünür bir fark yaratmadan kodu karmaşıklaştırırdı.
 */
function parcaSec(bolut: SubtitleDto, dil: string, simdi: number): string | null {
  const tam = bolut.metinler?.[dil]
  if (!tam) return null

  const parcalar = parcala(tam)
  if (parcalar.length === 1) return parcalar[0]

  const bas = Date.parse(bolut.baslangic)
  const son = Date.parse(bolut.bitis)
  // max(1,...): sifir uzunlukta bolut teorik olarak mumkun ve bolme hatasi verir.
  const oran = (simdi - bas) / Math.max(1, son - bas)
  const i = Math.floor(oran * parcalar.length)
  return parcalar[Math.min(Math.max(i, 0), parcalar.length - 1)]
}

/** Metni ekrana sığan parçalara böler — kelime sınırından. */
function parcala(metin: string): string[] {
  const enFazla = MAX_SATIR * MAX_KARAKTER
  if (metin.length <= enFazla) return [metin]

  const parcalar: string[] = []
  let simdiki = ''
  for (const kelime of metin.split(/\s+/)) {
    const aday = simdiki ? `${simdiki} ${kelime}` : kelime
    if (aday.length > enFazla && simdiki) {
      parcalar.push(simdiki)
      simdiki = kelime
    } else {
      simdiki = aday
    }
  }
  if (simdiki) parcalar.push(simdiki)
  return parcalar
}

/**
 * Satır başına karakter — yayın standartlarının önerdiği aralık 37-42.
 * Daha uzun satırda göz satır sonunu bulmakta zorlanıyor.
 */
const MAX_KARAKTER = 38

/** En fazla iki satır. Standartların ortak kuralı; üçüncü satır görüntüyü kapatıyor. */
const MAX_SATIR = 2

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

/** HLS gecikmesi bildirim sıklığı. */
const BILDIRIM_MS = 60_000

/** Açılışta doldurulan geçmiş penceresi. */
const BACKFILL_MS = 60_000

/** Önbellek bu sayıyı aşınca eski kayıtlar budanıyor. */
const CACHE_LIMIT = 200

/**
 * ISO kod → görünen ad. Yalnızca YAYGIN diller için — sunucu
 * (`STT_TARGET_LANGS`) bunda olmayan bir kod verirse kod büyük harfle
 * olduğu gibi gösteriliyor, uygulama kırılmıyor.
 */
const DIL_ADLARI: Record<string, string> = {
  tr: 'Türkçe',
  de: 'Deutsch',
  ru: 'Русский',
  fr: 'Français',
  es: 'Español',
  it: 'Italiano',
  pt: 'Português',
  nl: 'Nederlands',
  pl: 'Polski',
  ar: 'العربية',
  zh: '中文',
  ja: '日本語',
  ko: '한국어',
  uk: 'Українська',
  ro: 'Română',
  bg: 'Български',
  cs: 'Čeština',
  sv: 'Svenska',
  fi: 'Suomi',
  da: 'Dansk',
  el: 'Ελληνικά',
  hu: 'Magyar',
  he: 'עברית',
  hi: 'हिन्दी',
  id: 'Bahasa Indonesia',
  vi: 'Tiếng Việt',
}

/**
 * Seçilebilir altyazı dilleri.
 *
 * `kapali` ve `en` (pivot) SABİT — geri kalanı sunucudan (.env:
 * `STT_TARGET_LANGS`) geldiği kadar, {@link altyaziDilleriOku}. Burada
 * sabit kodlanmıyor: yeni bir dil eklemek artık yalnızca .env değişikliği,
 * `docker compose build frontend` GEREKMİYOR (17 Ağustos, "tam dinamik dil").
 */
export function subtitleLangs(): { kod: string; ad: string }[] {
  return [
    { kod: 'kapali', ad: 'Altyazı yok' },
    ...altyaziDilleriOku().map((kod) => ({ kod, ad: dilAdi(kod) })),
    { kod: 'en', ad: 'English' },
  ]
}

/**
 * ISO dil kodu → görünen ad. `subtitleLangs()`'ın kullandığı aynı harita —
 * dil adı gösteren başka bir yerin (ör. admin panelin Triton model
 * kartları) tekrar sabit kodlamak yerine buraya başvurması için ayrı
 * dışa aktarıldı.
 */
export function dilAdi(kod: string): string {
  return DIL_ADLARI[kod] ?? kod.toUpperCase()
}
