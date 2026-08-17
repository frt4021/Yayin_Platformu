import { Fragment, useCallback, useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { adminSistemLogApi } from '@/api/endpoints'
import { SISTEM_LOG_SEVIYE, type SistemLogDto, type SistemLogSeviye } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
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
import { Loader2Icon } from 'lucide-react'
import { GuidedTour } from '@/components/tour/GuidedTour'
import { usePageTour } from '@/components/tour/usePageTour'
import { TourTrigger } from '@/components/tour/TourTrigger'
import {
  ADMIN_SISTEM_LOGLAR_TOUR_SEEN_KEY,
  ADMIN_SISTEM_LOGLAR_TOUR_STEPS,
} from '@/components/tour/adminSistemLoglarSteps'

/** Sayfa açıkken sık, aksi halde de makul bir sıklıkla — diğer admin sayfalarıyla aynı tempo. */
const REFRESH_MS = 15000

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

  const rehberTuru = usePageTour(ADMIN_SISTEM_LOGLAR_TOUR_SEEN_KEY)

  const load = useCallback(async (s: string, sv: SistemLogSeviye | 'HEPSI') => {
    try {
      const sonuc = await adminSistemLogApi.list({
        servis: s || undefined,
        seviye: sv === 'HEPSI' ? undefined : sv,
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
  useEffect(() => {
    const timer = setTimeout(() => void load(servis, seviye), 300)
    return () => clearTimeout(timer)
  }, [load, servis, seviye])

  useEffect(() => {
    const timer = setInterval(() => void load(servis, seviye), REFRESH_MS)
    return () => clearInterval(timer)
  }, [load, servis, seviye])

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">Sistem Logları</h1>
          <p className="text-sm text-muted-foreground">
            Tüm servislerin logları Türkçeye yorumlanmış halde — rutin gürültü süzülüyor,
            yalnızca bilinen bir hata/uyarı/başarı örüntüsüne uyan satırlar gösteriliyor.
          </p>
        </div>
        <TourTrigger onClick={rehberTuru.start} />
      </div>

      <div className="flex flex-wrap gap-3">
        <Select value={seviye} onValueChange={(v) => setSeviye(v as SistemLogSeviye | 'HEPSI')}>
          <SelectTrigger data-tour="sistemlog-seviye-filtre" className="h-9 w-40">
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

        <Input
          data-tour="sistemlog-servis-filtre"
          placeholder="Servis adına göre ara (örn. triton, video-worker)…"
          value={servis}
          onChange={(e) => setServis(e.target.value)}
          className="max-w-xs"
        />
      </div>

      {error ? (
        <div className="rounded-lg border border-destructive/40 p-4 text-sm text-destructive">
          {error}
        </div>
      ) : (
        <div data-tour="sistemlog-tablo" className="rounded-xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Zaman</TableHead>
                <TableHead>Servis</TableHead>
                <TableHead data-tour="sistemlog-seviye-sutun">Seviye</TableHead>
                <TableHead>Mesaj</TableHead>
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
                loglar.map((log, i) => {
                  const anahtar = `${log.zaman}-${i}`
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
                          <Badge variant={seviyeRozetVaryanti(log.seviye)}>
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

      <GuidedTour
        open={rehberTuru.open}
        onClose={rehberTuru.close}
        steps={ADMIN_SISTEM_LOGLAR_TOUR_STEPS}
      />
    </div>
  )
}
