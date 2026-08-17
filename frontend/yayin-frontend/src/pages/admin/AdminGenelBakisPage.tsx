import { useEffect, useState } from 'react'
import { formatBytes } from '@/api/upload'
import { adminAnalitikApi } from '@/api/endpoints'
import type {
  BilesenSaglikDurumu,
  CanliDurumDto,
  ServisMetrikleriDto,
  SistemSagligiOzetDto,
} from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { altyaziDilleriOku } from '@/player/oynaticiAyarlari'
import { dilAdi } from '@/player/SubtitleOverlay'
import { CheckCircle2Icon, Loader2Icon, XCircleIcon } from 'lucide-react'
import { TUR_ETIKET, turVariant } from './AdminEtkinliklerPage'
import { Olcum, StatKart } from './AdminAnalitikPage'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import {
  ADMIN_GENEL_BAKIS_TOUR_STEPS,
  ADMIN_GENEL_BAKIS_TOUR_SEEN_KEY,
} from '@/components/tour/adminGenelBakisSteps'

/** Genel Bakış verisi bu aralıkla otomatik tazelenir (AdminSistemLoglarPage ile aynı). */
const REFRESH_MS = 15000

/**
 * Triton model adı → okunur etiket. STT_TARGET_LANGS'a göre DİNAMİK —
 * eskiden burada sabit bir dil listesi (tr/de/ru) vardı, bir dil
 * eklenince/kaldırılınca panel güncel kalmıyordu. Artık sunucunun bildirdiği
 * aktif dil listesinden (bkz. oynaticiAyarlari.ts) türetiliyor, `subtitleLangs()`
 * ile aynı kaynak — yeni bir dil eklemek burada da kod değişikliği gerektirmez.
 */
function tritonModelEtiketleri(): [string, string][] {
  return [
    ['whisper', 'Whisper (çözümleme)'],
    ...altyaziDilleriOku().map((kod): [string, string] => [`marian_en_${kod}`, `${dilAdi(kod)} çeviri`]),
  ]
}

function BilesenKarti({ durum }: { durum: BilesenSaglikDurumu }) {
  return (
    <div
      className={cn(
        'flex items-start gap-3 rounded-xl border p-4',
        !durum.saglikli && 'border-destructive/40 bg-destructive/5',
      )}
    >
      {durum.saglikli ? (
        <CheckCircle2Icon className="mt-0.5 size-5 shrink-0 text-status-success" />
      ) : (
        <XCircleIcon className="mt-0.5 size-5 shrink-0 text-destructive" />
      )}
      <div>
        <div className="font-medium">{durum.bilesen}</div>
        <div className="text-sm text-muted-foreground">{durum.detay}</div>
      </div>
    </div>
  )
}

/**
 * Admin panelin giriş ekranı — canlı durum (izleyici/dinleyici/trafik),
 * bileşen sağlığı (veritabanı, yayınlar, MediaMTX, MinIO, Triton, Keycloak,
 * Redis) ve son 10 etkinlik tek ekranda. Yeni bir izleme noktası gerekmedi:
 * sağlık kontrolleri canlı sorgular, etkinlik akışı mevcut
 * {@code etkinlik_kayitlari} verisi.
 */
export function AdminGenelBakisPage() {
  const tur = usePageTour(ADMIN_GENEL_BAKIS_TOUR_SEEN_KEY)
  const [veri, setVeri] = useState<SistemSagligiOzetDto | null>(null)
  const [canliDurum, setCanliDurum] = useState<CanliDurumDto | null>(null)
  const [servisMetrikleri, setServisMetrikleri] = useState<ServisMetrikleriDto | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const yukle = () =>
      Promise.all([
        adminAnalitikApi.genelBakis().then(setVeri),
        adminAnalitikApi.canliDurum().then(setCanliDurum),
        adminAnalitikApi.servisMetrikleri().then(setServisMetrikleri),
      ]).finally(() => setLoading(false))

    void yukle()
    // Sayfa acildiktan sonra ELLE yenilemeden guncel kalsin diye periyodik
    // tazeleme -- AdminSistemLoglarPage ile ayni desen/aralik.
    const timer = setInterval(() => void yukle(), REFRESH_MS)
    return () => clearInterval(timer)
  }, [])

  if (loading) {
    return (
      <div className="grid place-items-center py-20 text-muted-foreground">
        <Loader2Icon className="animate-spin" />
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <div className="flex items-center gap-2">
          <h1 className="text-3xl font-semibold tracking-tight">Genel Bakış</h1>
          <TourTrigger onClick={tur.start} />
        </div>
        <p className="text-sm text-muted-foreground">
          Canlı durum, sistem sağlığı ve son etkinlikler.
        </p>
      </div>

      <section data-tour="canli-durum" className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Canlı Durum</h2>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatKart baslik="Eşzamanlı izleyici">{canliDurum?.esZamanliIzleyici ?? 0}</StatKart>
          <StatKart baslik="Eşzamanlı dinleyici">{canliDurum?.esZamanliDinleyici ?? 0}</StatKart>
          <StatKart baslik="Aktif DVR kaydı">{canliDurum?.aktifDvrKaydi ?? 0}</StatKart>
          <StatKart baslik="Anlık trafik">
            <Olcum deger={canliDurum?.anlikTrafikMbps ?? null} birim="Mbps" />
          </StatKart>
        </div>
      </section>

      <section data-tour="sistem-sagligi" className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Sistem Sağlığı</h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {(veri?.bilesenler ?? []).map((durum) => (
            <BilesenKarti key={durum.bilesen} durum={durum} />
          ))}
        </div>
      </section>

      <section data-tour="servis-metrikleri" className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Servis Metrikleri</h2>
        <p className="text-sm text-muted-foreground">
          Yukarıdaki sağlık kartları yalnızca erişilebilir mi diyor — burası Prometheus'tan
          okunan gerçek sayılar. Bir servisin Prometheus'a henüz hiç veri göndermediği ya da
          Prometheus'a ulaşılamadığı durumlarda ilgili alan "ölçülmüyor" gösterir.
        </p>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <StatKart baslik="Triton — istek sayısı (5 dk)">
            <Olcum deger={servisMetrikleri?.tritonIstekSayisi5dk ?? null} />
          </StatKart>
          <StatKart baslik="Triton — ortalama gecikme (toplam)">
            <Olcum deger={servisMetrikleri?.tritonOrtalamaGecikmeMs ?? null} birim="ms" />
          </StatKart>
          {/* display:contents: grid duzenini bozmadan bu kartlari tur icin
              tek bir hedef altinda gruplamak icin -- sarmalayici kendi bir
              grid hucresi acmiyor, cocuklari direkt ust grid'e katiliyor. */}
          <div data-tour="triton-model-gecikme" className="contents">
            {tritonModelEtiketleri().map(([model, etiket]) => (
              <StatKart key={model} baslik={`Triton — ${etiket} gecikme`}>
                <Olcum deger={servisMetrikleri?.tritonModelGecikmeMs?.[model] ?? null} birim="ms" />
              </StatKart>
            ))}
          </div>
          <StatKart baslik="Triton — GPU bellek kullanımı">
            {servisMetrikleri?.tritonGpuBellekBayt != null ? (
              formatBytes(servisMetrikleri.tritonGpuBellekBayt)
            ) : (
              <Olcum deger={null} />
            )}
          </StatKart>

          <StatKart baslik="Postgres — aktif bağlantı">
            <Olcum deger={servisMetrikleri?.postgresAktifBaglanti ?? null} />
          </StatKart>
          <StatKart baslik="Postgres — veritabanı boyutu">
            {servisMetrikleri?.postgresBoyutBayt != null ? (
              formatBytes(servisMetrikleri.postgresBoyutBayt)
            ) : (
              <Olcum deger={null} />
            )}
          </StatKart>
          <StatKart baslik="Postgres — commit oranı (5 dk)">
            <Olcum deger={servisMetrikleri?.postgresCommitOrani5dk ?? null} birim="/sn" />
          </StatKart>

          <StatKart baslik="Redis — bağlı istemci">
            <Olcum deger={servisMetrikleri?.redisBagliIstemci ?? null} />
          </StatKart>
          <StatKart baslik="Redis — bellek kullanımı">
            {servisMetrikleri?.redisBellekBayt != null ? (
              formatBytes(servisMetrikleri.redisBellekBayt)
            ) : (
              <Olcum deger={null} />
            )}
          </StatKart>
          <StatKart baslik="Redis — komut oranı (5 dk)">
            <Olcum deger={servisMetrikleri?.redisKomutOrani5dk ?? null} birim="/sn" />
          </StatKart>

          <StatKart baslik="MinIO — kullanılan / toplam kapasite">
            {servisMetrikleri?.minioKullanilanBayt != null &&
            servisMetrikleri?.minioToplamBayt != null ? (
              `${formatBytes(servisMetrikleri.minioKullanilanBayt)} / ${formatBytes(servisMetrikleri.minioToplamBayt)}`
            ) : (
              <Olcum deger={null} />
            )}
          </StatKart>
          <StatKart baslik="MediaMTX — aktif path">
            <Olcum deger={servisMetrikleri?.mediaMtxAktifPath ?? null} />
          </StatKart>
          <StatKart baslik="MediaMTX — aktif HLS muxer">
            <Olcum deger={servisMetrikleri?.mediaMtxAktifHlsMuxer ?? null} />
          </StatKart>
        </div>
      </section>

      <section data-tour="son-etkinlikler" className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Son Etkinlikler</h2>
        <div className="rounded-xl border">
          {(veri?.sonEtkinlikler ?? []).length === 0 ? (
            <div className="py-8 text-center text-sm text-muted-foreground">Henüz etkinlik yok.</div>
          ) : (
            <ul className="divide-y">
              {(veri?.sonEtkinlikler ?? []).map((kayit) => (
                <li key={kayit.id} className="flex items-center justify-between gap-3 px-4 py-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <Badge variant={turVariant(kayit.tur)}>{TUR_ETIKET[kayit.tur]}</Badge>
                    <span className="text-sm text-muted-foreground">
                      {kayit.kullaniciAdi ?? '—'}
                    </span>
                    {kayit.hedefAdi && (
                      <span className="truncate text-sm text-muted-foreground">
                        · {kayit.hedefAdi}
                      </span>
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
      </section>

      <GuidedTour open={tur.open} onClose={tur.close} steps={ADMIN_GENEL_BAKIS_TOUR_STEPS} />
    </div>
  )
}
