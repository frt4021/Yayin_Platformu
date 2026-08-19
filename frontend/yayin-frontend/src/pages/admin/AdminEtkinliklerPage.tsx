import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { adminEtkinlikApi } from '@/api/endpoints'
import { ETKINLIK_TURLERI, type EtkinlikDto, type EtkinlikTuru } from '@/api/types'
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
import { DownloadIcon, Loader2Icon, SearchIcon } from 'lucide-react'
import { Sayfalama } from './Sayfalama'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import {
  ADMIN_ETKINLIKLER_TOUR_SEEN_KEY,
  ADMIN_ETKINLIKLER_TOUR_STEPS,
} from '@/components/tour/adminEtkinliklerSteps'

const MAX = 50

export const TUR_ETIKET: Record<EtkinlikTuru, string> = {
  GIRIS: 'Giriş',
  GIRIS_BASARISIZ: 'Giriş başarısız',
  CIKIS: 'Çıkış',
  IZLEME_BASLADI: 'İzleme başladı',
  IZLEME_BITTI: 'İzleme bitti',
  DINLEME_BASLADI: 'Dinleme başladı',
  DINLEME_BITTI: 'Dinleme bitti',
  ALTYAZI_DIL_DEGISTI: 'Altyazı dili değişti',
  KALITE_DEGISTI: 'Kalite değişti',
  DVR_GERI_SARILDI: 'DVR geri sarıldı',
  KLIP_OLUSTURULDU: 'Klip oluşturuldu',
  KAYIT_BASLADI: 'Kayıt başladı',
  KAYIT_DURDU: 'Kayıt durdu',
  KANAL_EKLENDI: 'Kanal eklendi',
  KANAL_SILINDI: 'Kanal silindi',
  RADYO_EKLENDI: 'Radyo eklendi',
  RADYO_SILINDI: 'Radyo silindi',
  KULLANICI_EKLENDI: 'Kullanıcı eklendi',
  KULLANICI_SILINDI: 'Kullanıcı silindi',
  KULLANICI_ROLU_DEGISTI: 'Kullanıcı rolü değişti',
  VIDEO_YUKLENDI: 'Video yüklendi',
  VIDEO_SILINDI: 'Video silindi',
  OYNATMA_HATASI: 'Oynatma hatası',
  OYNATMA_TAKILMA: 'Oynatma takılması',
}

export function turVariant(tur: EtkinlikTuru) {
  if (tur.endsWith('BASARISIZ') || tur.endsWith('SILINDI') || tur === 'OYNATMA_HATASI') {
    return 'destructive' as const
  }
  if (tur.endsWith('BASLADI') || tur.endsWith('EKLENDI') || tur === 'GIRIS') return 'default' as const
  return 'secondary' as const
}

/** Sunucunun {@code AdminEtkinlikResource}'ta izin verdiği üst sınır — dışa aktarımda sayfa başına bu kadar çekiliyor. */
const DISA_AKTAR_PARCA = 200

function csvHucre(deger: string): string {
  if (/[",\n]/.test(deger)) {
    return '"' + deger.replace(/"/g, '""') + '"'
  }
  return deger
}

/** {@code detay} JSON'undan kısa, okunabilir bir özet — kolon başına ham JSON basmak yerine. */
function detaySummary(kayit: EtkinlikDto): string {
  const d = kayit.detay
  switch (kayit.tur) {
    case 'IZLEME_BITTI':
    case 'DINLEME_BITTI': {
      const sureMs = Number(d.sureMs ?? 0)
      const sn = Math.round(sureMs / 1000)
      return `${Math.floor(sn / 60)} dk ${sn % 60} sn (${String(d.sebep ?? '')})`
    }
    case 'KULLANICI_ROLU_DEGISTI':
      return `${String(d.eskiRol ?? '?')} → ${String(d.yeniRol ?? '?')}`
    case 'ALTYAZI_DIL_DEGISTI':
      return String(d.dil ?? '—')
    case 'KALITE_DEGISTI':
      return String(d.kalite || 'otomatik')
    case 'DVR_GERI_SARILDI':
      return `${String(d.dakika ?? '?')} dk`
    case 'KAYIT_DURDU':
      return `${String(d.sureSn ?? '?')} sn`
    case 'OYNATMA_HATASI':
    case 'OYNATMA_TAKILMA':
      return `${String(d.sayi ?? '?')} olay`
    default: {
      const entries = Object.entries(d)
      if (entries.length === 0) return '—'
      return entries.map(([k, v]) => `${k}: ${v}`).join(', ')
    }
  }
}

export function AdminEtkinliklerPage() {
  const [items, setItems] = useState<EtkinlikDto[]>([])
  const [total, setTotal] = useState(0)
  const [first, setFirst] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [tur, setTur] = useState<EtkinlikTuru | 'HEPSI'>('HEPSI')
  const [kullaniciAdi, setKullaniciAdi] = useState('')

  const rehberTuru = usePageTour(ADMIN_ETKINLIKLER_TOUR_SEEN_KEY)

  const load = useCallback(async (f: number, t: EtkinlikTuru | 'HEPSI', ad: string) => {
    setLoading(true)
    setError(null)
    try {
      const sayfa = await adminEtkinlikApi.list({
        tur: t === 'HEPSI' ? undefined : t,
        kullaniciAdi: ad || undefined,
        first: f,
        max: MAX,
      })
      setItems(sayfa.items)
      setTotal(sayfa.total)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Etkinlikler yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(0, tur, kullaniciAdi)
    setFirst(0)
    // Arama/filtre değiştiğinde ilk sayfaya dönülüyor — aksi halde `first`
    // yeni filtrede anlamsız bir kaydırma noktasına işaret ederdi.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tur, kullaniciAdi])

  function sayfaDegis(yeniFirst: number) {
    setFirst(yeniFirst)
    void load(yeniFirst, tur, kullaniciAdi)
  }

  const [disaAktariliyor, setDisaAktariliyor] = useState(false)

  /**
   * Şu anki filtreyle eşleşen TÜM kayıtları (yalnızca ekrandaki sayfayı
   * değil) CSV olarak indirir. Sunucuda ayrı bir uç yok — mevcut listeleme
   * ucu {@code AdminEtkinlikResource}'un izin verdiği üst sınırla
   * ({@code DISA_AKTAR_PARCA}) sayfalanarak tüketiliyor.
   */
  async function disaAktar() {
    setDisaAktariliyor(true)
    try {
      const hepsi: EtkinlikDto[] = []
      let f = 0
      while (true) {
        const sayfa = await adminEtkinlikApi.list({
          tur: tur === 'HEPSI' ? undefined : tur,
          kullaniciAdi: kullaniciAdi || undefined,
          first: f,
          max: DISA_AKTAR_PARCA,
        })
        hepsi.push(...sayfa.items)
        if (sayfa.items.length === 0 || hepsi.length >= sayfa.total) break
        f += DISA_AKTAR_PARCA
      }

      const basliklar = ['Zaman', 'Kullanıcı', 'Tür', 'Hedef', 'Detay']
      const satirlar = hepsi.map((k) => [
        new Date(k.olusturmaZamani).toLocaleString('tr-TR'),
        k.kullaniciAdi ?? '',
        TUR_ETIKET[k.tur],
        k.hedefAdi ?? k.hedefTuru ?? '',
        detaySummary(k),
      ])
      // BOM: Excel, BOM'suz UTF-8'de Türkçe karakterleri bozuk gösteriyor.
      const BOM = '﻿'
      const csv = BOM + [basliklar, ...satirlar].map((s) => s.map(csvHucre).join(',')).join('\n')
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `etkinlikler-${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Dışa aktarılamadı.')
    } finally {
      setDisaAktariliyor(false)
    }
  }

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight">Kullanıcı Etkinliği</h1>
            <p className="text-sm text-muted-foreground">
              Giriş/çıkış, izleme/dinleme oturumları ve admin/içerik eylemlerinin denetim izi.
            </p>
          </div>
          <TourTrigger onClick={rehberTuru.start} />
        </div>

        <div className="relative">
          <SearchIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <input
            data-tour="etkinlik-kullanici-filtre"
            type="search"
            placeholder="Kullanıcı adına göre ara…"
            aria-label="Kullanıcı adına göre ara"
            value={kullaniciAdi}
            onChange={(e) => setKullaniciAdi(e.target.value)}
            className="h-10 w-72 rounded-full border bg-card pl-11 pr-4 text-sm
                       placeholder:text-muted-foreground focus:outline-none
                       focus:ring-2 focus:ring-[var(--ring)]"
          />
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Select value={tur} onValueChange={(v) => setTur(v as EtkinlikTuru | 'HEPSI')}>
          <SelectTrigger data-tour="etkinlik-tur-filtre" className="h-9 w-56 rounded-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="HEPSI">Tüm türler</SelectItem>
            {ETKINLIK_TURLERI.map((t) => (
              <SelectItem key={t} value={t}>
                {TUR_ETIKET[t]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Button
          variant="secondary"
          className="rounded-full"
          disabled={disaAktariliyor || total === 0}
          onClick={() => void disaAktar()}
          title="Şu anki filtreyle eşleşen tüm kayıtları CSV olarak indir"
        >
          {disaAktariliyor ? <Loader2Icon className="animate-spin" /> : <DownloadIcon />}
          Dışa Aktar
        </Button>
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div data-tour="etkinlik-tablo" className="rounded-2xl border bg-panel shadow-sm">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="uppercase tracking-wide text-[11px]">Zaman</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Kullanıcı</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Tür</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Hedef</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Detay</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                    <Loader2Icon className="mx-auto animate-spin" />
                  </TableCell>
                </TableRow>
              )}

              {!loading && items.length === 0 && (
                <TableRow>
                  <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                    Kayıt bulunamadı.
                  </TableCell>
                </TableRow>
              )}

              {!loading &&
                items.map((kayit) => (
                  <TableRow key={kayit.id}>
                    <TableCell className="whitespace-nowrap text-muted-foreground">
                      {new Date(kayit.olusturmaZamani).toLocaleString('tr-TR')}
                    </TableCell>
                    <TableCell className="font-medium">{kayit.kullaniciAdi ?? '—'}</TableCell>
                    <TableCell>
                      <Badge variant={turVariant(kayit.tur)}>{TUR_ETIKET[kayit.tur]}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {kayit.hedefAdi ?? kayit.hedefTuru ?? '—'}
                    </TableCell>
                    <TableCell className="text-muted-foreground">{detaySummary(kayit)}</TableCell>
                  </TableRow>
                ))}
            </TableBody>
          </Table>
        </div>
      )}

      <div data-tour="etkinlik-sayfalama">
        <Sayfalama first={first} max={MAX} total={total} loading={loading} onSayfaDegis={sayfaDegis} />
      </div>

      <GuidedTour
        open={rehberTuru.open}
        onClose={rehberTuru.close}
        steps={ADMIN_ETKINLIKLER_TOUR_STEPS}
      />
    </div>
  )
}
