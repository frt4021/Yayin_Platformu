import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { adminEtkinlikApi } from '@/api/endpoints'
import { ETKINLIK_TURLERI, type EtkinlikDto, type EtkinlikTuru } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
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
import { ChevronLeftIcon, ChevronRightIcon, Loader2Icon } from 'lucide-react'
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
  VIDEO_IZLEME_BASLADI: 'Video izleme başladı',
  VIDEO_IZLEME_BITTI: 'Video izleme bitti',
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
    case 'VIDEO_IZLEME_BITTI': {
      const dilimler = Array.isArray(d.ziyaretEdilenDilimler) ? d.ziyaretEdilenDilimler.length : 0
      return `${dilimler}/10 dilim${d.tamamlandi ? ', tamamlandı' : ''}`
    }
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

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">Kullanıcı Etkinliği</h1>
          <p className="text-sm text-muted-foreground">
            Giriş/çıkış, izleme/dinleme oturumları ve admin/içerik eylemlerinin denetim izi.
          </p>
        </div>
        <TourTrigger onClick={rehberTuru.start} />
      </div>

      <div className="flex flex-wrap gap-3">
        <Select value={tur} onValueChange={(v) => setTur(v as EtkinlikTuru | 'HEPSI')}>
          <SelectTrigger data-tour="etkinlik-tur-filtre" className="h-9 w-56">
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

        <Input
          data-tour="etkinlik-kullanici-filtre"
          placeholder="Kullanıcı adına göre ara…"
          value={kullaniciAdi}
          onChange={(e) => setKullaniciAdi(e.target.value)}
          className="max-w-xs"
        />
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div data-tour="etkinlik-tablo" className="rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Zaman</TableHead>
                <TableHead>Kullanıcı</TableHead>
                <TableHead>Tür</TableHead>
                <TableHead>Hedef</TableHead>
                <TableHead>Detay</TableHead>
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

      <div data-tour="etkinlik-sayfalama" className="flex items-center justify-between text-sm text-muted-foreground">
        <span>
          {total === 0 ? '0' : `${first + 1}–${Math.min(first + MAX, total)}`} / {total}
        </span>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={first === 0 || loading}
            onClick={() => sayfaDegis(Math.max(0, first - MAX))}
          >
            <ChevronLeftIcon />
            Önceki
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={first + MAX >= total || loading}
            onClick={() => sayfaDegis(first + MAX)}
          >
            Sonraki
            <ChevronRightIcon />
          </Button>
        </div>
      </div>

      <GuidedTour
        open={rehberTuru.open}
        onClose={rehberTuru.close}
        steps={ADMIN_ETKINLIKLER_TOUR_STEPS}
      />
    </div>
  )
}
