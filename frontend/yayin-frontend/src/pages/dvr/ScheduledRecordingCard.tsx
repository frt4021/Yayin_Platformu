import { useCallback, useEffect, useState } from 'react'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { scheduledRecordingsApi } from '@/api/endpoints'
import type { ScheduledRecordingDto, ScheduledStatus } from '@/api/types'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { CalendarClockIcon, Loader2Icon, XIcon } from 'lucide-react'

/**
 * Planlı kayıt: kullanıcının önceden verdiği kayıt emri.
 *
 * <p>Zaman çizelgesinden ayrı bir giriş yolu olmasının sebebi, çizelgenin
 * yalnızca <b>geçmişi</b> gösterebilmesi — gelecekteki bir aralık orada
 * seçilemez. Buradaki form ikisini de kabul ediyor; aralık geçmişteyse sunucu
 * beklemeden klibi açıyor.
 */
export function ScheduledRecordingCard({
  channelId,
  /** Çizelgede seçili aralık varsa forma taşınabilsin diye. */
  selection,
}: {
  channelId: string | null
  selection: { start: Date; end: Date } | null
}) {
  const [plans, setPlans] = useState<ScheduledRecordingDto[]>([])
  const [baslangic, setBaslangic] = useState('')
  const [bitis, setBitis] = useState('')
  const [saving, setSaving] = useState(false)

  const refresh = useCallback(async () => {
    try {
      setPlans(await scheduledRecordingsApi.list())
    } catch {
      // Liste tazelenemedi. Sessiz: form yine calisiyor ve her 30 saniyede
      // yeniden deneniyor -- her denemede hata gostermek kullaniciyi bogar.
    }
  }, [])

  // Emirler sunucuda kendiliginden ilerliyor (BEKLIYOR -> KAYITTA ->
  // TAMAMLANDI). Yoklama olmasaydi kullanici sayfayi yenilemeden durumun
  // degistigini goremezdi.
  useEffect(() => {
    void refresh()
    const timer = setInterval(() => void refresh(), 30_000)
    return () => clearInterval(timer)
  }, [refresh])

  /** Çizelgedeki seçimi forma taşır — aynı aralığı elle yazmak zorunda kalmasın. */
  function seciminiAl() {
    if (!selection) return
    setBaslangic(toLocalInput(selection.start))
    setBitis(toLocalInput(selection.end))
  }

  async function submit() {
    if (!channelId || !baslangic || !bitis) return
    setSaving(true)
    try {
      const plan = await scheduledRecordingsApi.create(channelId, {
        // datetime-local yerel saat veriyor; sunucu UTC bekliyor.
        baslangic: new Date(baslangic).toISOString(),
        bitis: new Date(bitis).toISOString(),
      })
      toast.success(
        plan.durum === 'TAMAMLANDI'
          ? 'Aralık geçmişte olduğu için klip hemen kuyruğa alındı.'
          : 'Kayıt emri alındı.',
        {
          description:
            plan.durum === 'BEKLIYOR'
              ? 'Aralık geldiğinde kayıt kendiliğinden başlayacak.'
              : plan.hata ?? 'Klipler sayfasından takip edebilirsiniz.',
        },
      )
      setBaslangic('')
      setBitis('')
      await refresh()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'Kayıt emri verilemedi.')
    } finally {
      setSaving(false)
    }
  }

  async function cancel(id: string) {
    try {
      await scheduledRecordingsApi.cancel(id)
      toast.success('Emir iptal edildi.')
      await refresh()
    } catch (e) {
      toast.error(e instanceof ApiError ? e.message : 'İptal edilemedi.')
    }
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-base">
          <CalendarClockIcon className="size-4" />
          Planlı kayıt
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <p className="text-xs text-muted-foreground">
          Gelecekteki bir saat aralığı için kayıt emri verin. Kanalın geriye sarması
          kapalıysa yalnızca o aralık boyunca açılır.
        </p>

        <div className="grid gap-2 sm:grid-cols-2">
          <div className="flex flex-col gap-1">
            <Label className="text-xs" htmlFor="plan-baslangic">
              Başlangıç
            </Label>
            <Input
              id="plan-baslangic"
              type="datetime-local"
              value={baslangic}
              onChange={(e) => setBaslangic(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label className="text-xs" htmlFor="plan-bitis">
              Bitiş
            </Label>
            <Input
              id="plan-bitis"
              type="datetime-local"
              value={bitis}
              onChange={(e) => setBitis(e.target.value)}
            />
          </div>
        </div>

        <div className="flex gap-2">
          <Button
            className="flex-1"
            onClick={() => void submit()}
            disabled={saving || !channelId || !baslangic || !bitis}
          >
            {saving ? <Loader2Icon className="animate-spin" /> : <CalendarClockIcon />}
            Kayıt emri ver
          </Button>
          {selection && (
            <Button variant="outline" onClick={seciminiAl} title="Çizelgedeki seçimi kullan">
              Seçimden al
            </Button>
          )}
        </div>

        {plans.length > 0 && (
          <div className="flex flex-col gap-1.5 border-t pt-3">
            <span className="text-xs text-muted-foreground">Emirlerim</span>
            {plans.slice(0, 8).map((plan) => (
              <div
                key={plan.id}
                className="flex items-center gap-2 rounded-md border px-2 py-1.5 text-xs"
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate font-medium">{plan.channelName}</div>
                  <div className="text-muted-foreground">
                    {new Date(plan.baslangic).toLocaleString('tr-TR')} —{' '}
                    {new Date(plan.bitis).toLocaleTimeString('tr-TR')}
                  </div>
                  {/* Basarisiz emirde sebebi gostermek sart: kullanici aksi
                      halde neden klip olusmadigini hicbir yerden ogrenemez. */}
                  {plan.hata && <div className="mt-0.5 text-destructive">{plan.hata}</div>}
                </div>
                <Badge variant={DURUM_RENGI[plan.durum]}>{DURUM_ETIKETI[plan.durum]}</Badge>
                {(plan.durum === 'BEKLIYOR' || plan.durum === 'KAYITTA') && (
                  <Button
                    size="icon"
                    variant="ghost"
                    className="size-6"
                    title="İptal et"
                    onClick={() => void cancel(plan.id)}
                  >
                    <XIcon className="size-3" />
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

const DURUM_ETIKETI: Record<ScheduledStatus, string> = {
  BEKLIYOR: 'bekliyor',
  KAYITTA: 'kayıtta',
  TAMAMLANDI: 'tamamlandı',
  BASARISIZ: 'başarısız',
  IPTAL: 'iptal',
}

const DURUM_RENGI: Record<ScheduledStatus, 'outline' | 'success' | 'error'> = {
  BEKLIYOR: 'outline',
  KAYITTA: 'success',
  TAMAMLANDI: 'success',
  BASARISIZ: 'error',
  IPTAL: 'outline',
}

/**
 * {@code datetime-local} girdisinin beklediği biçim.
 *
 * <p>{@code toISOString()} kullanılamaz: UTC'ye çevirip kullanıcının yerel
 * saatinden kaydırırdı — seçtiği aralık formda başka bir saat olarak görünürdü.
 */
function toLocalInput(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}
