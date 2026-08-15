import { useEffect, useState } from 'react'
import { formatBytes } from '@/api/upload'
import { adminAnalitikApi } from '@/api/endpoints'
import { cn } from '@/lib/utils'
import type {
  CanliDurumDto,
  DepolamaDto,
  GenelAktiviteDto,
  IcerikPerformansiDto,
  TeknikDto,
  TopEtiketDto,
  VideoAnalitikOzetDto,
  VideoIsiHaritasiDto,
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
    <div className="rounded-xl border p-4">
      <div className="text-sm text-muted-foreground">{baslik}</div>
      <div className="mt-1 text-2xl font-semibold">{children}</div>
    </div>
  )
}

function TopListe({ baslik, veri }: { baslik: string; veri: TopEtiketDto[] }) {
  return (
    <div className="rounded-xl border">
      <div className="border-b px-4 py-3 text-sm font-medium">{baslik}</div>
      <Table>
        <TableBody>
          {veri.length === 0 && (
            <TableRow>
              <TableCell className="py-6 text-center text-muted-foreground">Veri yok.</TableCell>
            </TableRow>
          )}
          {veri.map((e) => (
            <TableRow key={e.id}>
              <TableCell className="font-medium">{e.ad}</TableCell>
              <TableCell className="text-right text-muted-foreground">{e.sayi}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}

/**
 * 10 dilimlik kaba tekrar-izleme dağılımı — el yapımı bar chart (projede
 * hiçbir chart kütüphanesi yok, mevcut sade-div üslubu korunuyor). Tek renk,
 * yoğunluk opaklıkla — tek seri olduğu için birden fazla tonu ayırt etme
 * derdi yok.
 */
function DilimGrafigi({ dilimSayaclari }: { dilimSayaclari: number[] }) {
  const maks = Math.max(1, ...dilimSayaclari)
  return (
    <div className="flex items-end gap-1 rounded-xl border p-4" style={{ height: 140 }}>
      {dilimSayaclari.map((sayi, i) => (
        <div key={i} className="flex flex-1 flex-col items-center gap-1">
          <div
            title={`İlk %${i * 10}–%${(i + 1) * 10}: ${sayi} oturum`}
            className="w-full rounded-t bg-primary"
            style={{
              height: Math.max(4, (sayi / maks) * 96),
              opacity: 0.15 + 0.85 * (sayi / maks),
            }}
          />
          <span className="text-[10px] text-muted-foreground">%{i * 10}</span>
        </div>
      ))}
    </div>
  )
}

/**
 * Analitik dashboard'u — kullanıcının önerdiği beş modül + video tamamlanma
 * oranı/ısı haritası bölümü (Faz 2). Henüz enstrümantasyonu olmayan
 * alanlar (bant genişliği) sessizce sıfır
 * göstermek yerine açıkça "ölçülmüyor" olarak işaretleniyor.
 */
export function AdminAnalitikPage() {
  const [canliDurum, setCanliDurum] = useState<CanliDurumDto | null>(null)
  const [icerik, setIcerik] = useState<IcerikPerformansiDto | null>(null)
  const [depolama, setDepolama] = useState<DepolamaDto | null>(null)
  const [teknik, setTeknik] = useState<TeknikDto | null>(null)
  const [genel, setGenel] = useState<GenelAktiviteDto | null>(null)
  const [videolar, setVideolar] = useState<VideoAnalitikOzetDto[]>([])
  const [loading, setLoading] = useState(true)

  const [secilenVideoId, setSecilenVideoId] = useState<string | null>(null)
  const [isiHaritasi, setIsiHaritasi] = useState<VideoIsiHaritasiDto | null>(null)
  const [isiHaritasiYukleniyor, setIsiHaritasiYukleniyor] = useState(false)

  useEffect(() => {
    void Promise.all([
      adminAnalitikApi.canliDurum().then(setCanliDurum),
      adminAnalitikApi.icerikPerformansi().then(setIcerik),
      adminAnalitikApi.depolama().then(setDepolama),
      adminAnalitikApi.teknik().then(setTeknik),
      adminAnalitikApi.genel().then(setGenel),
      adminAnalitikApi.videoListesi().then(setVideolar),
    ]).finally(() => setLoading(false))
  }, [])

  function videoSec(videoId: string) {
    setSecilenVideoId(videoId)
    setIsiHaritasiYukleniyor(true)
    void adminAnalitikApi
      .videoIsiHaritasi(videoId)
      .then(setIsiHaritasi)
      .finally(() => setIsiHaritasiYukleniyor(false))
  }

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
      <div>
        <h1 className="text-3xl font-semibold tracking-tight">Analitik</h1>
        <p className="text-sm text-muted-foreground">
          Kullanıcı davranışı, içerik/depolama ve video izleme özeti. Henüz ölçülmeyen alanlar
          açıkça işaretli.
        </p>
      </div>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Canlı Sistem Durumu</h2>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatKart baslik="Eşzamanlı izleyici">{canliDurum?.esZamanliIzleyici ?? 0}</StatKart>
          <StatKart baslik="Eşzamanlı dinleyici">{canliDurum?.esZamanliDinleyici ?? 0}</StatKart>
          <StatKart baslik="Aktif DVR kaydı">{canliDurum?.aktifDvrKaydi ?? 0}</StatKart>
          <StatKart baslik="Anlık trafik">
            <Olcum deger={canliDurum?.anlikTrafikMbps ?? null} birim="Mbps" />
          </StatKart>
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">İçerik & Kanal Performansı</h2>
        <div className="grid gap-3 md:grid-cols-3">
          <TopListe baslik="En çok izlenen kanallar" veri={icerik?.enCokIzlenenKanallar ?? []} />
          <TopListe baslik="En çok dinlenen radyolar" veri={icerik?.enCokDinlenenRadyolar ?? []} />
          <TopListe baslik="En çok kaydedilen yayınlar" veri={icerik?.enCokKaydedilenYayinlar ?? []} />
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Depolama ve DVR Analitiği</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          <StatKart baslik="Önümüzdeki 24 saatte planlı kayıt">
            {depolama?.gelecek24SaatPlanliKayit ?? 0}
          </StatKart>
          <StatKart baslik="Toplam DVR boyutu">
            {formatBytes(depolama?.toplamDvrBoyutBayt ?? 0)}
          </StatKart>
        </div>
        <div className="rounded-xl border">
          <div className="border-b px-4 py-3 text-sm font-medium">En yüksek kotalı kullanıcılar</div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Kullanıcı</TableHead>
                <TableHead className="text-right">Kullanım</TableHead>
                <TableHead className="text-right">Kota %</TableHead>
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

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Teknik & Hata Takibi</h2>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          <StatKart baslik="Başarısız planlı kayıt">{teknik?.basarisizPlanliKayit ?? 0}</StatKart>
          <StatKart baslik="Video işleme hatası">{teknik?.videoIslemeHatasi ?? 0}</StatKart>
          <StatKart baslik="Yayın kopma oranı">
            <Olcum deger={teknik?.yayinKopmaOrani ?? null} birim="%" />
          </StatKart>
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Genel Kullanıcı Aktivitesi</h2>
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

      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-medium">Video Tamamlanma Oranı & Isı Haritası</h2>
        <div className="rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Başlık</TableHead>
                <TableHead className="text-right">Oturum</TableHead>
                <TableHead className="text-right">Tamamlanma %</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {videolar.length === 0 && (
                <TableRow>
                  <TableCell colSpan={3} className="py-6 text-center text-muted-foreground">
                    Veri yok.
                  </TableCell>
                </TableRow>
              )}
              {videolar.map((v) => (
                <TableRow
                  key={v.videoId}
                  onClick={() => videoSec(v.videoId)}
                  className={cn(
                    'cursor-pointer',
                    secilenVideoId === v.videoId && 'bg-accent',
                  )}
                >
                  <TableCell className="font-medium">{v.baslik}</TableCell>
                  <TableCell className="text-right text-muted-foreground">{v.oturumSayisi}</TableCell>
                  <TableCell className="text-right text-muted-foreground">
                    {v.tamamlanmaOrani.toFixed(0)}%
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>

        {secilenVideoId && (
          <div className="grid gap-3 sm:grid-cols-[200px_1fr]">
            <StatKart baslik="Tamamlanma oranı">
              {isiHaritasiYukleniyor ? (
                <Loader2Icon className="size-5 animate-spin text-muted-foreground" />
              ) : (
                `${(isiHaritasi?.tamamlanmaOrani ?? 0).toFixed(0)}%`
              )}
            </StatKart>
            {isiHaritasi && <DilimGrafigi dilimSayaclari={isiHaritasi.dilimSayaclari} />}
          </div>
        )}
      </section>
    </div>
  )
}
