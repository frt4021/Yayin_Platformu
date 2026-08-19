import { Fragment, useCallback, useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { adminSistemLogApi } from '@/api/endpoints'
import { SISTEM_LOG_SEVIYE, type SistemLogDto, type SistemLogSeviye } from '@/api/types'
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
import { Loader2Icon, RefreshCwIcon, SearchIcon } from 'lucide-react'
import { Sayfalama } from './Sayfalama'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import {
  ADMIN_SISTEM_LOGLAR_TOUR_SEEN_KEY,
  ADMIN_SISTEM_LOGLAR_TOUR_STEPS,
} from '@/components/tour/adminSistemLoglarSteps'

/** Sayfa açıkken sık, aksi halde de makul bir sıklıkla — diğer admin sayfalarıyla aynı tempo. */
const REFRESH_MS = 15000

/** Loki'den tek seferde çekilen üst sınır — backend zaten 1000'de kesiyor. */
const CEKME_LIMIT = 500

/** Ekranda sayfa başına gösterilen satır — çekilen küme üzerinde İSTEMCİ tarafında bölünüyor. */
const SAYFA_BOYU = 20

const SEVIYE_ETIKET: Record<SistemLogSeviye, string> = {
  HATA: 'Hata',
  UYARI: 'Uyarı',
  BASARI: 'Başarı',
  BILGI: 'Bilgi',
}

function seviyeRozetVaryanti(seviye: SistemLogSeviye) {
  switch (seviye) {
    case 'HATA':
      return 'error' as const
    case 'UYARI':
      return 'warning' as const
    case 'BASARI':
      return 'success' as const
    case 'BILGI':
      return 'outline' as const
  }
}

/**
 * Admin panelin "Sistem Logları" ekranı — tüm konteynerlerin loglarını
 * (Loki üzerinden) Türkçeye yorumlanmış halde gösterir. Backend zaten
 * yalnızca bilinen bir örüntüye uyan ya da genel hata/uyarı sinyali
 * taşıyan satırları döndürüyor (bkz. {@code SistemLogYorumlayici}) — rutin
 * gürültü (health-check tekrarları, erişim logları vb.) hiç gelmiyor.
 */
export function AdminSistemLoglarPage() {
  const [loglar, setLoglar] = useState<SistemLogDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [servis, setServis] = useState('')
  const [seviye, setSeviye] = useState<SistemLogSeviye | 'HEPSI'>('HEPSI')
  const [acikSatir, setAcikSatir] = useState<string | null>(null)
  const [first, setFirst] = useState(0)

  const rehberTuru = usePageTour(ADMIN_SISTEM_LOGLAR_TOUR_SEEN_KEY)

  const load = useCallback(async (s: string, sv: SistemLogSeviye | 'HEPSI') => {
    try {
      const sonuc = await adminSistemLogApi.list({
        servis: s || undefined,
        seviye: sv === 'HEPSI' ? undefined : sv,
        limit: CEKME_LIMIT,
      })
      setLoglar(sonuc)
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Loglar yüklenemedi.')
    } finally {
      setLoading(false)
    }
  }, [])

  // Servis alanında her tuşta istek atmamak için gecikme; seviye değişince hemen tazele.
  // Filtre değişince ilk sayfaya dönülüyor.
  useEffect(() => {
    const timer = setTimeout(() => {
      setFirst(0)
      void load(servis, seviye)
    }, 300)
    return () => clearTimeout(timer)
  }, [load, servis, seviye])

  useEffect(() => {
    const timer = setInterval(() => void load(servis, seviye), REFRESH_MS)
    return () => clearInterval(timer)
  }, [load, servis, seviye])

  // Sayfalama İSTEMCİ tarafında: Loki bir SQL tablosu değil, sunucudan
  // "sayfa N'i ver" diye istenemiyor — zaten çekilmiş son 24 saatlik küme
  // üzerinde bölünüyor.
  const gorunenler = loglar.slice(first, first + SAYFA_BOYU)

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight">Sistem Logları</h1>
            <p className="text-sm text-muted-foreground">
              Tüm servislerin logları Türkçeye yorumlanmış halde — rutin gürültü süzülüyor,
              yalnızca bilinen bir hata/uyarı/başarı örüntüsüne uyan satırlar gösteriliyor.
            </p>
          </div>
          <TourTrigger onClick={rehberTuru.start} />
        </div>

        <div className="relative">
          <SearchIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <input
            data-tour="sistemlog-servis-filtre"
            type="search"
            placeholder="Servis adına göre ara (örn. triton, video-worker)…"
            aria-label="Servis adına göre ara"
            value={servis}
            onChange={(e) => setServis(e.target.value)}
            className="h-10 w-80 rounded-full border bg-card pl-11 pr-4 text-sm
                       placeholder:text-muted-foreground focus:outline-none
                       focus:ring-2 focus:ring-[var(--ring)]"
          />
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <Select value={seviye} onValueChange={(v) => setSeviye(v as SistemLogSeviye | 'HEPSI')}>
          <SelectTrigger data-tour="sistemlog-seviye-filtre" className="h-9 w-40 rounded-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="HEPSI">Tüm seviyeler</SelectItem>
            {SISTEM_LOG_SEVIYE.map((s) => (
              <SelectItem key={s} value={s}>
                {SEVIYE_ETIKET[s]}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Button
          variant="outline"
          size="icon"
          className="rounded-full"
          disabled={loading}
          onClick={() => void load(servis, seviye)}
          title="Şimdi yenile"
        >
          <RefreshCwIcon className={loading ? 'animate-spin' : undefined} />
        </Button>
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div data-tour="sistemlog-tablo" className="rounded-2xl border bg-panel shadow-sm">
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead className="uppercase tracking-wide text-[11px]">Zaman</TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Servis</TableHead>
                <TableHead data-tour="sistemlog-seviye-sutun" className="uppercase tracking-wide text-[11px]">
                  Seviye
                </TableHead>
                <TableHead className="uppercase tracking-wide text-[11px]">Mesaj</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {loading && (
                <TableRow>
                  <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                    <Loader2Icon className="mx-auto animate-spin" />
                  </TableCell>
                </TableRow>
              )}

              {!loading && loglar.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                    Gösterilecek bir şey yok — bu iyi bir işaret, hiçbir bilinen hata/uyarı
                    örüntüsü tetiklenmedi.
                  </TableCell>
                </TableRow>
              )}

              {!loading &&
                gorunenler.map((log, i) => {
                  const anahtar = `${log.zaman}-${first + i}`
                  const acik = acikSatir === anahtar
                  return (
                    <Fragment key={anahtar}>
                      <TableRow
                        className="cursor-pointer"
                        onClick={() => setAcikSatir(acik ? null : anahtar)}
                      >
                        <TableCell className="whitespace-nowrap text-muted-foreground">
                          {new Date(log.zaman).toLocaleString('tr-TR')}
                        </TableCell>
                        <TableCell className="font-medium">{log.servis}</TableCell>
                        <TableCell>
                          <Badge variant={seviyeRozetVaryanti(log.seviye)} className="gap-1.5">
                            {log.seviye === 'HATA' && (
                              <span className="size-1.5 animate-pulse rounded-full bg-status-error" />
                            )}
                            {SEVIYE_ETIKET[log.seviye]}
                          </Badge>
                        </TableCell>
                        <TableCell>{log.mesaj}</TableCell>
                      </TableRow>
                      {acik && (
                        <TableRow>
                          <TableCell colSpan={4} className="bg-accent/30 py-2">
                            <div className="font-mono text-xs text-muted-foreground">
                              {log.hamMesaj}
                            </div>
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })}
            </TableBody>
          </Table>
        </div>
      )}

      {!error && !loading && loglar.length > 0 && (
        <div className="flex flex-col gap-1">
          <Sayfalama
            first={first}
            max={SAYFA_BOYU}
            total={loglar.length}
            loading={loading}
            onSayfaDegis={setFirst}
          />
          {/* Loki bir SQL tablosu degil -- bu gercek toplam sistem-genelindeki
              eslesme sayisi degil, son 24 saatten cekilen (en fazla
              CEKME_LIMIT) kumenin boyutu. Bunu acikca soylemezsek "toplam
              12.450 log" gibi yanlis bir izlenim verirdi. */}
          {loglar.length >= CEKME_LIMIT && (
            <p className="text-xs text-muted-foreground">
              Son 24 saatten en fazla {CEKME_LIMIT} kayıt çekiliyor — daha eski ya da fazlası için
              servis/seviye ile daraltın.
            </p>
          )}
        </div>
      )}

      <GuidedTour
        open={rehberTuru.open}
        onClose={rehberTuru.close}
        steps={ADMIN_SISTEM_LOGLAR_TOUR_STEPS}
      />
    </div>
  )
}
