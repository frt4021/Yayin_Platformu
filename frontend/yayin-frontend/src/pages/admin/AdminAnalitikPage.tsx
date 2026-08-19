import { useEffect, useState } from 'react'
import { formatBytes } from '@/api/upload'
import { adminAnalitikApi } from '@/api/endpoints'
import type {
  CanliDurumDto,
  DepolamaDto,
  GenelAktiviteDto,
  IcerikPerformansiDto,
  TeknikDto,
  TopEtiketDto,
} from '@/api/types'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Loader2Icon } from 'lucide-react'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import {
  ADMIN_ANALITIK_TOUR_SEEN_KEY,
  ADMIN_ANALITIK_TOUR_STEPS,
} from '@/components/tour/adminAnalitikSteps'

/** "Henüz ölçülmüyor" alanları için — sessizce sıfır göstermek yanıltıcı olurdu. */
export function Olcum({ deger, birim }: { deger: number | null; birim?: string }) {
  if (deger === null) {
    return <span className="text-muted-foreground italic">ölçülmüyor</span>
  }
  return (
    <span>
      {deger.toLocaleString('tr-TR')}
      {birim ? ` ${birim}` : ''}
    </span>
  )
}

export function StatKart({ baslik, children }: { baslik: string; children: React.ReactNode }) {
  return (
    <div className="rounded-2xl border bg-panel p-4">
      <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{baslik}</div>
      <div className="mt-1.5 text-2xl font-semibold tracking-tight">{children}</div>
    </div>
  )
}

/** Bölüm başlığı — altındaki ince çizgi ile bölümleri ayırıyor. */
export function BolumBasligi({ children }: { children: React.ReactNode }) {
  return <h2 className="border-b pb-2 text-lg font-semibold tracking-tight">{children}</h2>
}

function TopListe({ baslik, veri }: { baslik: string; veri: TopEtiketDto[] }) {
  return (
    <div className="rounded-2xl border bg-panel">
      <div className="border-b px-4 py-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {baslik}
      </div>
      {veri.length === 0 ? (
        <div className="py-6 text-center text-sm text-muted-foreground">Veri yok.</div>
      ) : (
        <div className="divide-y">
          {veri.map((e) => (
            <div key={e.id} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
              <span className="min-w-0 truncate font-medium">{e.ad}</span>
              <span className="tabular-nums text-muted-foreground">{e.sayi}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Analitik dashboard'u — kullanıcının önerdiği beş modül. Henüz
 * enstrümantasyonu olmayan alanlar (bant genişliği) sessizce sıfır
 * göstermek yerine açıkça "ölçülmüyor" olarak işaretleniyor.
 */
export function AdminAnalitikPage() {
  const [canliDurum, setCanliDurum] = useState<CanliDurumDto | null>(null)
  const [icerik, setIcerik] = useState<IcerikPerformansiDto | null>(null)
  const [depolama, setDepolama] = useState<DepolamaDto | null>(null)
  const [teknik, setTeknik] = useState<TeknikDto | null>(null)
  const [genel, setGenel] = useState<GenelAktiviteDto | null>(null)
  const [loading, setLoading] = useState(true)

  const rehberTuru = usePageTour(ADMIN_ANALITIK_TOUR_SEEN_KEY)

  useEffect(() => {
    void Promise.all([
      adminAnalitikApi.canliDurum().then(setCanliDurum),
      adminAnalitikApi.icerikPerformansi().then(setIcerik),
      adminAnalitikApi.depolama().then(setDepolama),
      adminAnalitikApi.teknik().then(setTeknik),
      adminAnalitikApi.genel().then(setGenel),
    ]).finally(() => setLoading(false))
  }, [])

  if (loading) {
    return (
      <div className="grid place-items-center py-20 text-muted-foreground">
        <Loader2Icon className="animate-spin" />
      </div>
    )
  }

  const enYogunSaat = genel
    ? Object.entries(genel.saatBazliGiris).sort((a, b) => b[1] - a[1])[0]
    : undefined

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">Analitik</h1>
          <p className="text-sm text-muted-foreground">
            Kullanıcı davranışı, içerik/depolama ve video izleme özeti. Henüz ölçülmeyen alanlar
            açıkça işaretli.
          </p>
        </div>
        <TourTrigger onClick={rehberTuru.start} />
      </div>

      <section data-tour="analitik-canli-durum" className="flex flex-col gap-3">
        <BolumBasligi>Canlı Sistem Durumu</BolumBasligi>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatKart baslik="Eşzamanlı izleyici">{canliDurum?.esZamanliIzleyici ?? 0}</StatKart>
          <StatKart baslik="Eşzamanlı dinleyici">{canliDurum?.esZamanliDinleyici ?? 0}</StatKart>
          <StatKart baslik="Aktif DVR kaydı">{canliDurum?.aktifDvrKaydi ?? 0}</StatKart>
          <StatKart baslik="Anlık trafik">
            <Olcum deger={canliDurum?.anlikTrafikMbps ?? null} birim="Mbps" />
          </StatKart>
        </div>
      </section>

      <section data-tour="analitik-icerik" className="flex flex-col gap-3">
        <BolumBasligi>İçerik & Kanal Performansı</BolumBasligi>
        <div className="grid gap-3 md:grid-cols-3">
          <TopListe baslik="En çok izlenen kanallar" veri={icerik?.enCokIzlenenKanallar ?? []} />
          <TopListe baslik="En çok dinlenen radyolar" veri={icerik?.enCokDinlenenRadyolar ?? []} />
          <TopListe baslik="En çok kaydedilen yayınlar" veri={icerik?.enCokKaydedilenYayinlar ?? []} />
        </div>
      </section>

      <section data-tour="analitik-depolama" className="flex flex-col gap-3">
        <BolumBasligi>Depolama ve DVR Analitiği</BolumBasligi>
        <div className="grid gap-3 sm:grid-cols-2">
          <StatKart baslik="Önümüzdeki 24 saatte planlı kayıt">
            {depolama?.gelecek24SaatPlanliKayit ?? 0}
          </StatKart>
          <StatKart baslik="Toplam DVR boyutu">
            {formatBytes(depolama?.toplamDvrBoyutBayt ?? 0)}
          </StatKart>
        </div>
        <div className="rounded-2xl border bg-panel">
          <div className="border-b px-4 py-3 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            En yüksek kotalı kullanıcılar
          </div>
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="uppercase tracking-wide text-[11px]">Kullanıcı</TableHead>
                <TableHead className="text-right uppercase tracking-wide text-[11px]">Kullanım</TableHead>
                <TableHead className="text-right uppercase tracking-wide text-[11px]">Kota %</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(depolama?.enYuksekKullanicilar ?? []).length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} className="py-6 text-center text-muted-foreground">
                    Veri yok.
                  </TableCell>
                </TableRow>
              )}
              {(depolama?.enYuksekKullanicilar ?? []).map((k) => (
                <TableRow key={k.kullaniciAdi}>
                  <TableCell className="font-medium">{k.kullaniciAdi}</TableCell>
                  <TableCell className="text-right text-muted-foreground">
                    {formatBytes(k.toplamBayt)}
                  </TableCell>
                  <TableCell className="text-right text-muted-foreground">{k.yuzde}%</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </section>

      <section data-tour="analitik-teknik" className="flex flex-col gap-3">
        <BolumBasligi>Teknik & Hata Takibi</BolumBasligi>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <StatKart baslik="Başarısız planlı kayıt">{teknik?.basarisizPlanliKayit ?? 0}</StatKart>
          <StatKart baslik="Video işleme hatası">{teknik?.videoIslemeHatasi ?? 0}</StatKart>
          <StatKart baslik="Yayın kopma oranı">
            <Olcum deger={teknik?.yayinKopmaOrani ?? null} birim="%" />
          </StatKart>
        </div>
      </section>

      <section data-tour="analitik-genel" className="flex flex-col gap-3">
        <BolumBasligi>Genel Kullanıcı Aktivitesi</BolumBasligi>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatKart baslik="Günlük aktif kullanıcı (DAU)">{genel?.dau ?? 0}</StatKart>
          <StatKart baslik="Aylık aktif kullanıcı (MAU)">{genel?.mau ?? 0}</StatKart>
          <StatKart baslik="En yoğun saat">
            {enYogunSaat ? `${enYogunSaat[0]}:00` : '—'}
          </StatKart>
          <StatKart baslik="Kullanıcı başına ort. kanal açma (24s)">
            {(genel?.ortalamaIzlemeBaslangici24s ?? 0).toFixed(1)}
          </StatKart>
        </div>
      </section>

      <GuidedTour
        open={rehberTuru.open}
        onClose={rehberTuru.close}
        steps={ADMIN_ANALITIK_TOUR_STEPS}
      />
    </div>
  )
}
