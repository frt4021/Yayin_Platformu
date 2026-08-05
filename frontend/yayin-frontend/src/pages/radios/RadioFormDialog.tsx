import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { radiosApi } from '@/api/endpoints'
import type { RadioDto, RadioSourceKind } from '@/api/types'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import { Loader2Icon } from 'lucide-react'

/** Backend'in mediamtx_path için uyguladığı kural; aynısını burada da sınıyoruz. */
const PATH_PATTERN = /^[A-Za-z0-9_-]+$/

const EMPTY = {
  name: '',
  sourceUrl: '',
  // Varsayılan KOPRU: radyoların büyük çoğunluğu Icecast/Shoutcast üzerinden
  // düz MP3 veriyor ve bu mod olmadan çalışmıyorlar.
  sourceKind: 'KOPRU' as RadioSourceKind,
  mediamtxPath: '',
  bitrate: '128k',
  active: true,
  logoUrl: '',
  sortOrder: 0,
}

/** Seçeneklerin ne anlama geldiği; yanlış seçim sessizce çalışmayan bir radyo üretiyor. */
const KIND_INFO: Record<RadioSourceKind, { label: string; hint: string; example: string }> = {
  KOPRU: {
    label: 'Icecast / düz ses akışı',
    hint:
      'MediaMTX düz MP3 okuyamıyor — içeride bir ffmpeg süreci akışı çekip AAC’ye kodluyor. ' +
      'Radyoların çoğu bu gruba giriyor. Ölçülen maliyet: istasyon başına ~%2.6 CPU.',
    example: 'http://yayin.ornek.com:8000/canli',
  },
  DOGRUDAN: {
    label: 'HLS / RTSP / RTMP / SRT',
    hint:
      'Adres MediaMTX’e kaynak olarak verilir, ffmpeg araya girmez. Ek CPU maliyeti yok. ' +
      'Yalnızca kaynak gerçekten bu protokollerden biriyse çalışır.',
    example: 'https://ornek.com/radyo/playlist.m3u8',
  },
}

/**
 * Radyo ekleme ve düzenleme.
 *
 * @param radio null ise yeni radyo, doluysa düzenleme.
 */
export function RadioFormDialog({
  radio,
  open,
  onOpenChange,
  onSaved,
}: {
  radio: RadioDto | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved: () => void
}) {
  const [form, setForm] = useState(EMPTY)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Dialog her açıldığında düzenlenen radyonun değerleriyle doldurulur;
  // aksi halde bir önceki kaydın verisi formda kalırdı.
  useEffect(() => {
    if (!open) return
    setError(null)
    setForm(
      radio
        ? {
            name: radio.name,
            sourceUrl: radio.sourceUrl,
            sourceKind: radio.sourceKind,
            mediamtxPath: radio.mediamtxPath,
            bitrate: radio.bitrate,
            active: radio.active,
            logoUrl: radio.logoUrl ?? '',
            sortOrder: radio.sortOrder,
          }
        : EMPTY,
    )
  }, [open, radio])

  function set<K extends keyof typeof EMPTY>(key: K, value: (typeof EMPTY)[K]) {
    setForm((prev) => ({ ...prev, [key]: value }))
  }

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()

    const payload = {
      name: form.name.trim(),
      sourceUrl: form.sourceUrl.trim(),
      sourceKind: form.sourceKind,
      mediamtxPath: form.mediamtxPath.trim(),
      bitrate: form.bitrate.trim(),
      active: form.active,
      logoUrl: form.logoUrl.trim(),
      sortOrder: form.sortOrder,
    }

    if (!PATH_PATTERN.test(payload.mediamtxPath)) {
      setError('Path yalnızca harf, rakam, alt çizgi ve tire içerebilir.')
      return
    }

    setError(null)
    setBusy(true)
    try {
      if (radio) {
        await radiosApi.update(radio.id, payload)
        toast.success(`${payload.name} güncellendi.`)
      } else {
        await radiosApi.create(payload)
        toast.success(`${payload.name} oluşturuldu.`)
      }
      onOpenChange(false)
      onSaved()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Radyo kaydedilemedi.')
    } finally {
      setBusy(false)
    }
  }

  const kind = KIND_INFO[form.sourceKind]

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{radio ? 'Radyoyu düzenle' : 'Yeni radyo'}</DialogTitle>
          <DialogDescription>
            Radyo aktifken MediaMTX'e yazılır ve yayın çekilmeye başlanır.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={onSubmit} className="flex min-h-0 flex-1 flex-col gap-4">
          <DialogBody className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <Label htmlFor="radioName">İstasyon adı</Label>
              <Input
                id="radioName"
                required
                maxLength={128}
                value={form.name}
                onChange={(e) => set('name', e.target.value)}
              />
            </div>

            {/* Kaynak türü en üstte ve kart olarak: yanlış seçimin cezası görünür
                bir hata değil, hiç başlamayan bir yayın. */}
            <div className="flex flex-col gap-2">
              <Label>Kaynak türü</Label>
              <div className="grid gap-2 sm:grid-cols-2">
                {(['KOPRU', 'DOGRUDAN'] as RadioSourceKind[]).map((value) => (
                  <button
                    key={value}
                    type="button"
                    onClick={() => set('sourceKind', value)}
                    className={cn(
                      'rounded-lg border p-3 text-left transition-colors',
                      form.sourceKind === value
                        ? 'border-primary bg-accent/40'
                        : 'hover:bg-accent/30',
                    )}
                  >
                    <div className="text-sm font-medium">{KIND_INFO[value].label}</div>
                    <div className="mt-0.5 truncate font-mono text-xs text-muted-foreground">
                      {KIND_INFO[value].example}
                    </div>
                  </button>
                ))}
              </div>
              <p className="text-xs text-muted-foreground">{kind.hint}</p>
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="radioSource">Kaynak adresi</Label>
              <Input
                id="radioSource"
                required
                maxLength={512}
                placeholder={kind.example}
                value={form.sourceUrl}
                onChange={(e) => set('sourceUrl', e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                Adres bir ffmpeg komutuna gömüldüğü için boşluk, tırnak ve kabuk
                karakterleri kabul edilmiyor.
              </p>
            </div>

            <div className="flex flex-col gap-2">
              <Label htmlFor="radioPath">MediaMTX path</Label>
              <Input
                id="radioPath"
                required
                maxLength={128}
                placeholder="radyo1"
                value={form.mediamtxPath}
                onChange={(e) => set('mediamtxPath', e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                Dinleme adresi bundan türer:{' '}
                <code>…:8888/{form.mediamtxPath || 'path'}/index.m3u8</code>
              </p>
              {radio && radio.mediamtxPath !== form.mediamtxPath.trim() && (
                <p className="text-xs text-destructive">
                  Path değişiyor — mevcut dinleyicilerin adresi geçersiz olacak.
                </p>
              )}
            </div>

            {form.sourceKind === 'KOPRU' && (
              <div className="flex flex-col gap-2">
                <Label htmlFor="radioBitrate">Bit hızı</Label>
                <Input
                  id="radioBitrate"
                  maxLength={16}
                  placeholder="128k"
                  value={form.bitrate}
                  onChange={(e) => set('bitrate', e.target.value)}
                  className="w-32"
                />
                <p className="text-xs text-muted-foreground">
                  <strong>Kaynağınkinin üzerine çıkmayın.</strong> 64k'lık bir yayını
                  128k'ya kodlamak kaliteyi artırmaz, yalnızca bant genişliği harcar —
                  kanallardaki çözünürlük merdiveniyle aynı kural.
                </p>
              </div>
            )}

            <div className="flex flex-col gap-2">
              <Label htmlFor="radioLogo">Logo adresi (isteğe bağlı)</Label>
              <Input
                id="radioLogo"
                maxLength={512}
                placeholder="https://ornek.com/logo.png"
                value={form.logoUrl}
                onChange={(e) => set('logoUrl', e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                Boş bırakılırsa istasyon adının baş harfleri gösterilir.
              </p>
            </div>

            <div className="flex flex-col gap-2 rounded-lg border p-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={form.active}
                  onChange={(e) => set('active', e.target.checked)}
                />
                Yayında olsun
              </label>

              <div className="flex items-center gap-2">
                <Label htmlFor="radioOrder" className="text-xs">
                  Sıra
                </Label>
                <Input
                  id="radioOrder"
                  type="number"
                  value={form.sortOrder}
                  onChange={(e) => set('sortOrder', Number(e.target.value) || 0)}
                  className="h-8 w-20"
                />
                <span className="text-xs text-muted-foreground">
                  Küçük olan üstte; eşitlerde ada göre sıralanır.
                </span>
              </div>
            </div>
          </DialogBody>

          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Vazgeç
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2Icon className="animate-spin" />}
              Kaydet
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
