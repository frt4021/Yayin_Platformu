import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { toast } from 'sonner'
import { ApiError } from '@/api/client'
import { channelsApi, clipsApi, recordingsApi, subtitlesApi } from '@/api/endpoints'
import type { ActiveRecordingDto, ChannelDto } from '@/api/types'
import { HlsPlayer, type CaptureHandle } from '@/components/HlsPlayer'
import { TileActions } from './TileActions'
import { subtitleLangs, SubtitleOverlay } from './SubtitleOverlay'
import { dvrAltyaziAcikMi } from '@/player/oynaticiAyarlari'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { MAX_TILES, usePlayers } from './PlayerContext'
import { qualitiesOf } from '@/lib/renditions'
import { LiveRewind, type LiveRewindHandle } from './LiveRewind'
import { PlayerControls } from './PlayerControls'
import { usePresence } from './usePresence'
import { useEffect as useEffectReact, useState as useStateReact } from 'react'
import { SearchIcon, SettingsIcon, Volume2Icon, VolumeXIcon, XIcon } from 'lucide-react'

/** İzleme sayfasının yolu; katman bu yolda içerik alanını kaplar, diğerlerinde mini olur. */
export const WATCH_PATH = '/izle'

/**
 * Zincirleme geri sarmada ("-10 sn" ile bir DVR parçasının da başına
 * gelince) bir sonraki parçanın kaç saniyelik olacağı — LiveRewind'in
 * tıklama varsayılanından (o, tıklanan andan şimdiye kadarki her şeyi
 * getirir) BAĞIMSIZ, sabit bir adım büyüklüğü seçildi çünkü burada "şimdi"
 * değil bir önceki parçanın BAŞI referans alınıyor.
 */
const CHAIN_STEP_SECONDS = 120

/** Karo sayısına göre sütun sayısı — 16 kanal 4x4'e oturur. */
function columnsFor(count: number) {
  if (count <= 1) return 1
  if (count <= 4) return 2
  if (count <= 9) return 3
  return 4
}

/**
 * Tüm HLS oynatıcılarını barındıran kalıcı katman.
 *
 * <p><b>Neden burada:</b> oynatıcılar route'un içinde yaşasaydı, kullanıcı
 * başka bir sayfaya geçtiğinde React onları unmount eder, video elementleri
 * yok olur ve yayın kesilirdi. Bu katman {@code AppLayout} içinde
 * {@code <Outlet/>}'in dışında duruyor; sayfa değişse de mount kalıyor.
 *
 * <p><b>Tek ağaç kuralı:</b> görünüm (izleme sayfası / mini / büyük ekran)
 * yalnızca CSS sınıflarıyla değişiyor, bileşen ağacı hiç değişmiyor. Farklı
 * durumlar için farklı JSX dalları döndürseydik React ağacı söker ve her
 * geçişte yayın baştan bağlanırdı. Aynı sebeple kanal başına <b>tek</b>
 * {@link HlsPlayer} var — büyük ekran ayrı bir oynatıcı kurmuyor, sadece
 * diğer karolar gizleniyor.
 */
export function PersistentPlayers() {
  const location = useLocation()
  const onWatchPage = location.pathname === WATCH_PATH

  // Kanal listesi context'ten: sagdaki yayin paneli de ayni listeyi okuyor ve
  // iki ayri yoklama, ikisinin kisa sureligine farkli durum gostermesine yol
  // aciyordu.
  const { channels, openIds, audioId, expandedId, quality, radioId, toggle, openMany, closeAll, setAudio, expand, setQuality } =
    usePlayers()

  const [recordings, setRecordings] = useState<ActiveRecordingDto[]>([])

  /**
   * Kanal çiplerini süzen arama.
   *
   * <p><b>Yalnızca istemcide.</b> Sunucuda kanal araması diye bir uç yok ve
   * eklemek de gereksiz: liste zaten tamamen belleğe alınmış durumda ve en
   * fazla birkaç düzine satır.
   */
  const [arama, setArama] = useState('')

  const loadRecordings = useCallback(async () => {
    try {
      setRecordings(await recordingsApi.active())
    } catch {
      // Kayit listesi alinamazsa dugme "kayit yok" durumunda kalir; oynatmayi
      // etkilemedigi icin kullaniciya hata gostermeye gerek yok.
    }
  }, [])

  useEffect(() => {
    void loadRecordings()
    // Kayit durumu periyodik tazeleniyor: kullanici kaydi baslatip baska
    // sayfaya gectiginde geri dondugunde dugmenin durumu guncel olmali,
    // yoksa "durdur" yerine "baslat" gorup tekrar baslatmaya calisir ya da
    // tam tersi — olmayan kaydi durdurmaya kalkar. Sayfa degisince bu
    // katman unmount olmuyor (AppLayout icinde, Outlet disinda) ama yine
    // de periyodik yoklama olmadan kaydin bittigi (ust sinira ulasti)
    // haberdar olunamiyor.
    const timer = setInterval(() => void loadRecordings(), 10_000)
    return () => clearInterval(timer)
  }, [loadRecordings])

  // openIds sırası korunuyor; kanal listesi tazelenince karolar yer değiştirmesin.
  const open = openIds
    .map((id) => channels.find((c) => c.id === id))
    .filter((c): c is ChannelDto => Boolean(c))

  const q = arama.trim().toLocaleLowerCase('tr')
  const playable = channels
    .filter((c) => c.active)
    // Acik olan kanal ARAMAYA RAGMEN listede kaliyor: aksi halde kullanici
    // arama yazdiginda acik kanalin kapatma dugmesi kayboluyordu.
    .filter((c) => !q || openIds.includes(c.id) || c.name.toLocaleLowerCase('tr').includes(q))
  const expanded = open.find((c) => c.id === expandedId) ?? null
  // Mini görünümde tek karo gösterilir: sesi olan, yoksa ilk açık kanal.
  const miniChannel = expanded ?? open.find((c) => c.id === audioId) ?? open[0] ?? null

  // Hiç açık kanal yoksa ve izleme sayfasında değilsek gösterilecek bir şey yok.
  // Bu durumda korunacak bir oynatıcı da olmadığı için unmount sakıncasız.
  if (open.length === 0 && !onWatchPage) return null

  const singleTile = !onWatchPage || expanded !== null

  function tileVisible(channel: ChannelDto) {
    if (!onWatchPage) return channel.id === miniChannel?.id
    if (expanded) return channel.id === expanded.id
    return true
  }

  return (
    <div
      className={cn(
        onWatchPage
          // Sol yan cubuk (w-60) ve sag yayin paneli (w-80) sabit konumlu;
          // katman onlarin ARASINA oturmali yoksa altlarina kayar.
          // Bu degerler AppLayout'takilerle birlikte degismek zorunda.
          ? 'fixed bottom-0 left-60 right-80 top-0 z-10 flex flex-col gap-5 bg-background px-6 pb-6 pt-5'
          : 'fixed right-4 z-50 w-80 overflow-hidden rounded-2xl border bg-card shadow-lg',
        // Radyo çubuğu (h-16) sayfanın altını kaplıyor; mini oynatıcı onun
        // üstüne çıkmalı yoksa ikisi üst üste biner.
        !onWatchPage && (radioId ? 'bottom-20' : 'bottom-4'),
      )}
    >
      {/* Arama — mini görünümde gizli. */}
      <div data-tour="arama" className={cn('relative', !onWatchPage && 'hidden')}>
        <SearchIcon className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <input
          type="search"
          value={arama}
          onChange={(e) => setArama(e.target.value)}
          placeholder="Kanal ara…"
          aria-label="Kanal ara"
          className="h-12 w-full rounded-full border bg-card pl-11 pr-4 text-sm
                     placeholder:text-muted-foreground focus:outline-none
                     focus:ring-2 focus:ring-[var(--ring)]"
        />
      </div>

      {/* Kanal seçim şeridi — mini görünümde gizli, ama DOM'da kalıyor ki
          kardeş sıralaması değişmesin ve karolar remount olmasın. */}
      {/* Başlık solda, denetimler sağda. Eskiden hepsi tek sıra hâlinde
          soldan diziliyordu ve sayfa başlığı kanal çipleriyle aynı ağırlıkta
          okunuyordu; "burası neresi" ile "ne açayım" ayrı iki soru. */}
      <div
        className={cn(
          'flex flex-wrap items-center justify-between gap-x-6 gap-y-3',
          !onWatchPage && 'hidden',
        )}
      >
        <div className="flex items-center gap-3">
          <h1 className="text-3xl font-semibold tracking-tight">İzleme</h1>
          <Badge variant="secondary" className="text-[13px]">
            {open.length} / {MAX_TILES}
          </Badge>
        </div>

        <div data-tour="kanal-cipleri" className="flex flex-wrap items-center gap-2">
          {playable.length === 0 && (
            <span className="text-sm text-muted-foreground">Yayında kanal yok.</span>
          )}
          {playable.map((channel) => {
            const isOpen = openIds.includes(channel.id)
            return (
              <Button
                key={channel.id}
                size="sm"
                // Açık kanal nane, kapalı kanal koyu gri dolgu. Kapalı olan
                // eskiden yalnızca çerçeveliydi ve zeminde kayboluyordu --
                // "kanal yok" ile "kanal kapalı" ayırt edilemiyordu.
                variant={isOpen ? 'default' : 'secondary'}
                disabled={!isOpen && openIds.length >= MAX_TILES}
                onClick={() => toggle(channel.id)}
                title={
                  channel.streaming === false ? 'Kanal aktif ama yayın akmıyor' : undefined
                }
              >
                {channel.name}
              </Button>
            )
          })}

          {/* Kanal çipleriyle toplu eylemler arasında nefes: bitişik
              dururken "Tümünü aç" bir kanal adı gibi okunuyordu. */}
          <span className="mx-1 h-6 w-px bg-border" />

          <span data-tour="toplu-eylemler" className="flex gap-2">
          <Button
            variant="outline"
            onClick={() => openMany(playable.map((c) => c.id))}
            disabled={playable.length === 0}
          >
            Tümünü aç
          </Button>
          <Button variant="outline" onClick={closeAll} disabled={open.length === 0}>
            Tümünü kapat
          </Button>
          </span>
        </div>
      </div>

      {/* Mini başlık — izleme sayfasında gizli. */}
      <div
        className={cn(
          'flex items-center justify-between gap-2 px-3 py-2 text-xs',
          onWatchPage && 'hidden',
        )}
      >
        <span className="truncate font-medium">{miniChannel?.name}</span>
        <div className="flex items-center gap-1">
          {open.length > 1 && <Badge variant="secondary">+{open.length - 1}</Badge>}
          <Button
            variant="ghost"
            size="icon"
            className="size-7"
            title="Tümünü kapat"
            onClick={closeAll}
          >
            <XIcon />
          </Button>
        </div>
      </div>

      {/* Karolar. Kapsayıcı hep aynı; yalnızca sütun sayısı ve karo görünürlüğü değişiyor.
          İzleme sayfasında karolar zeminden ayrışan yuvarlak bir panelin
          içinde duruyor: yayın görüntüsü doğrudan sayfa zeminine oturunca
          nerede bittiği belirsizleşiyor ve arayüz "kenarsız" görünüyordu. */}
      <div
        data-tour="karo-alani"
        className={
          onWatchPage
            ? 'min-h-0 flex-1 rounded-2xl border bg-panel p-3'
            : 'aspect-video bg-black'
        }
      >
        <div
          className="grid size-full gap-3"
          style={{
            gridTemplateColumns: `repeat(${singleTile ? 1 : columnsFor(open.length)}, minmax(0, 1fr))`,
          }}
        >
          {open.map((channel) => (
            <Tile
              key={channel.id}
              channel={channel}
              visible={tileVisible(channel)}
              expanded={expanded?.id === channel.id}
              compact={!onWatchPage}
              hasAudio={channel.id === audioId}
              quality={quality[channel.id] ?? ''}
              onQuality={(suffix) => {
                setQuality(channel.id, suffix)
                // Kullanıcı davranışı denetim izi için — oynatıcının kendi
                // akışını etkilemez, hata sessizce yutuluyor.
                void channelsApi.kaliteDegisti(channel.id, suffix).catch(() => {})
              }}
              showControls={singleTile}
              onToggleExpand={() => expand(expanded?.id === channel.id ? null : channel.id)}
              recording={recordings.find((r) => r.channelId === channel.id) ?? null}
              onRecordingChanged={() => void loadRecordings()}
              onAudio={() => setAudio(channel.id === audioId ? null : channel.id)}
              onClose={() => toggle(channel.id)}
            />
          ))}
        </div>
      </div>

      {open.length === 0 && onWatchPage && (
        <div className="pointer-events-none absolute inset-x-6 bottom-6 top-24 grid place-items-center rounded-2xl border border-dashed text-sm text-muted-foreground">
          Yukarıdan kanal seçin.
        </div>
      )}
    </div>
  )
}

function Tile({
  channel,
  visible,
  expanded,
  compact,
  hasAudio,
  quality,
  onQuality,
  showControls,
  recording,
  onRecordingChanged,
  onToggleExpand,
  onAudio,
  onClose,
}: {
  channel: ChannelDto
  recording: ActiveRecordingDto | null
  onRecordingChanged: () => void
  visible: boolean
  expanded: boolean
  /** Mini görünüm: üst çubuk ve kenarlık gösterilmez. */
  compact: boolean
  hasAudio: boolean
  /** Seçili rendition son eki; '' = kaynak. */
  quality: string
  onQuality: (suffix: string) => void
  /** Duraklatma/ses/tam ekran çubuğu — yalnızca tek karo görünürken. */
  showControls: boolean
  onToggleExpand: () => void
  onAudio: () => void
  onClose: () => void
}) {
  const qualities = qualitiesOf(channel)
  // Seçili kalite kanaldan kaldırılmış olabilir; o durumda kaynağa düş.
  const selected = qualities.find((q) => q.suffix === quality) ?? qualities[0]

  // MediaMTX'in reader sayısı DEĞİL: bu sekmenin gerçekten izlediğini
  // periyodik bildirir, backend'deki izleyici sayısı bundan hesaplanır.
  // Karo gizliyken de (mozaikte görünmeyen ama DOM'da kalıp çalmaya devam
  // eden) çağrılıyor -- o da gerçekten izlenen bir sekme.
  usePresence('channels', channel.id)

  /** Kare yakalama için oynatıcının video elementine erişim. */
  const captureRef = useRef<CaptureHandle | null>(null)

  /**
   * {@link LiveRewind}'in DVR'a geçiş komutuna erişim — canlı oynatıcının
   * kendi "-10 sn" düğmesi, HLS tamponu tükenince bunu çağırıyor (bkz.
   * {@code onBufferExceeded} altında).
   */
  const liveRewindRef = useRef<LiveRewindHandle | null>(null)

  /**
   * Altyazı dili. `kapali` = gösterme.
   *
   * Karo başına ayrı: mozaikte farklı kanallar farklı dilde izlenebilmeli ve
   * tek bir genel ayar bunu imkânsız kılardı.
   */
  const [subtitleLang, setSubtitleLang] = useState<string>('kapali')

  /** Geri sarılan bölümün blob adresi; null ise canlı akış oynuyor. */
  const [rewindUrl, setRewindUrl] = useStateReact<string | null>(null)

  /**
   * Şu an oynayan geri sarılmış parçanın BAŞLADIĞI mutlak an.
   *
   * <p>Kullanıcı bu parçanın da başına gelip "-10 sn" ile daha geriye
   * gitmeye çalışırsa ({@link handleBufferExceeded}), bir sonraki DVR
   * parçasının nereden isteneceğini buradan biliyoruz -- yoksa zincirleme
   * geri sarma yalnızca canlıdan İLK DVR parçasına geçerken çalışırdı,
   * parçadan parçaya devam edemezdi.
   */
  const [rewindStart, setRewindStart] = useStateReact<Date | null>(null)

  /**
   * Geri sarılmışken işaretlenmiş klibin başlangıç anı — TileActions'ta
   * DEĞİL burada tutuluyor: geri sarılan bölüm kendiliğinden bitip
   * {@link backToLive} tetiklenirse (aşağıya bkz.) klibin de o anda
   * otomatik bitirilmesi gerekiyor, bu karar TileActions'ın dışında verilmek
   * zorunda.
   */
  const [pendingClipStart, setPendingClipStart] = useStateReact<Date | null>(null)

  /**
   * Denetim çubuğunun bağlanacağı video elementi.
   *
   * <p>Ref değil <b>state</b>: elementin gelmesi yeniden render tetiklemeli,
   * yoksa çubuk ilk karede "video yok" görüp bir daha güncellenmezdi. Canlı
   * akış ve geri sarılmış mp4 aynı yere yazıyor -- ikisi de aynı çubuğu
   * kullanıyor.
   */
  const [videoEl, setVideoEl] = useStateReact<HTMLVideoElement | null>(null)

  /** Karonun kökü — tam ekran bunu alıyor (denetimler ve altyazı dahil). */
  const [tileEl, setTileEl] = useStateReact<HTMLDivElement | null>(null)

  // Blob'lar serbest bırakılmazsa her geri sarmada bellekte bir kopya birikir.
  useEffectReact(() => {
    return () => {
      if (rewindUrl) URL.revokeObjectURL(rewindUrl)
    }
  }, [rewindUrl])

  /**
   * {@link TileActions}'ın kare yakalama tutamağı — canlı akışta
   * {@code captureRef} (HlsPlayer'ınki) kullanılır. Geri sarılmışken AYRICA
   * kurulmalı: {@code rewindUrl} set olunca HlsPlayer tamamen unmount olur
   * ve kendi temizleme kodunda {@code captureRef.current = null} yazar —
   * geri sarılmış mp4'ün kendi video elementi (yukarıdaki `<video>`) hiç
   * captureRef'e bağlı değildi, bu yüzden kare yakalama geri sarılmışken
   * "Görüntü henüz hazır değil" hatası veriyordu (gerçek bug, 17 Ağustos).
   * {@code playingDate}, rewindStart + o an oynanan saniye ile geçmişteki
   * GERÇEK anı veriyor — DvrPage.tsx'teki aynı hesapla birebir aynı fikir.
   */
  const tileCapture: { current: CaptureHandle | null } = {
    current:
      rewindUrl && videoEl
        ? {
            video: videoEl,
            playingDate: () =>
              rewindStart
                ? new Date(rewindStart.getTime() + videoEl.currentTime * 1000)
                : new Date(),
            liveEdge: () => null,
            goLive: backToLive,
          }
        : captureRef.current,
  }

  /**
   * Canlıya dönüş — geri sarılan bölüm kendiliğinden bitince ({@code onEnded})
   * ya da kullanıcı "Canlı" düğmesine basınca çağrılıyor.
   *
   * <p>İşaretlenmiş bir klip varsa ({@code pendingClipStart}) burada otomatik
   * bitiriliyor: kullanıcı "durdur"a hiç basmadan bölüm bitip canlıya
   * dönerse, klip aralığın SONUNA kadar değil bitmemiş kalırdı (bir sonraki
   * "durdur" tıklamasında bitiş artık gerçek "şimdi" olurdu — rewind
   * noktasından çok daha ileriye giden KOCAMAN bir aralık, tam da
   * TileActions'taki simetri düzeltmesinin önlemeye çalıştığı bug). Bitiş
   * anı burada, state temizlenmeden ÖNCE hesaplanıyor: rewindStart +
   * videoEl.currentTime, yani bölümün GERÇEKTEN bittiği an.
   */
  function backToLive() {
    if (pendingClipStart) {
      const bitisAni =
        rewindStart && videoEl
          ? new Date(rewindStart.getTime() + videoEl.currentTime * 1000)
          : new Date()
      const baslangicAni = pendingClipStart
      setPendingClipStart(null)
      void clipsApi
        .create(channel.id, { start: baslangicAni.toISOString(), end: bitisAni.toISOString() })
        .then(() => {
          toast.success('Bölüm bitti, klip kuyruğa alındı.', {
            description: 'Hazır olunca Klipler sayfasından indirebilirsiniz.',
          })
          onRecordingChanged()
        })
        .catch((e) => {
          toast.error(e instanceof ApiError ? e.message : 'Klip oluşturulamadı.')
        })
    }
    if (rewindUrl) URL.revokeObjectURL(rewindUrl)
    setRewindUrl(null)
    setRewindStart(null)
  }

  /** {@code onRewind}: her yeni parça yüklendiğinde başlangıcını da sakla. */
  function onRewind(objectUrl: string, start: Date) {
    if (rewindUrl) URL.revokeObjectURL(rewindUrl)
    setRewindUrl(objectUrl)
    setRewindStart(start)
  }

  /**
   * Denetim çubuğundaki "-10 sn" tamponun (canlı ya da o an oynayan DVR
   * parçasının) dışına taştığında çağrılır.
   *
   * <p><b>İki durum:</b> hâlâ canlıysa ({@code rewindStart} yok),
   * {@code secondsBehindLive} canlı kenardan ne kadar geride kalındığını
   * söylüyor -- oradan DVR'a ilk geçişi yapıyoruz. Zaten geri sarılmış bir
   * parça izleniyorsa, o parçanın kendi başlangıcından bir öncekine
   * ZİNCİRLİYORUZ -- kullanıcı ne kadar art arda tıklarsa tıklasın DVR
   * kaydı bittiği (ya da 2 saatlik pencere sonu) yere kadar geriye
   * gidebilsin.
   */
  function handleBufferExceeded(secondsBehindLive: number) {
    if (rewindStart) {
      const hedef = new Date(rewindStart.getTime() - CHAIN_STEP_SECONDS * 1000)
      void liveRewindRef.current?.seekTo(hedef, CHAIN_STEP_SECONDS + 10)
    } else {
      void liveRewindRef.current?.seekTo(new Date(Date.now() - secondsBehindLive * 1000))
    }
  }

  return (
    <div
      ref={setTileEl}
      className={cn(
        'group relative min-h-0 overflow-hidden bg-black',
        !compact && 'rounded-xl border',
        // Gizli karonun oynatıcısı DOM'da kalıyor ve çalmaya devam ediyor;
        // kaldırılsaydı geri dönüldüğünde yayın baştan bağlanırdı.
        !visible && 'hidden',
      )}
    >
      {/* key: kalite değişince oynatıcı yeniden kurulmalı — hls.js kaynak
          adresini çalışırken değiştiremiyor. */}
      {rewindUrl ? (
        // Geri sarılan bölüm düz bir mp4; HLS oynatıcıya gerek yok.
        // Bittiğinde otomatik canlı yayına dönülüyor — kullanıcı "Canlı"
        // düğmesine basmak zorunda kalmasın.
        <video
          key={rewindUrl}
          ref={setVideoEl}
          src={rewindUrl}
          autoPlay
          muted={!hasAudio}
          onEnded={backToLive}
          className="size-full bg-black object-contain"
        />
      ) : (
      <HlsPlayer
        key={selected.hlsUrl}
        channelId={channel.id}
        captureRef={captureRef}
        onVideo={setVideoEl}
        src={selected.hlsUrl}
        muted={!hasAudio}
        // Yerleşik denetimler hiçbir zaman açılmıyor; tek karoda özel çubuk
        // deniyor, mozaikte hiç denetim olmuyor (16 çubuk görüntüyü boğar).
        controls={false}
        // Geride kalma rozeti yalnızca çubuk yokken: çubuktaki CANLI hapı
        // aynı işi yapıyor ve ikisi birlikte görününce hangisinin ne yaptığı
        // belirsizleşiyor.
        showLiveBadge={!showControls}
        className="size-full"
      />
      )}

      {/* Özel denetim çubuğu. Canlı akışta CANLI göstergesi ve canlıya dönüş
          var; geri sarılmış mp4'te canlı kenar yok, o yüzden geçilmiyor. */}
      {showControls && !compact && (
        <PlayerControls
          video={videoEl}
          container={tileEl}
          liveEdge={rewindUrl ? undefined : () => captureRef.current?.liveEdge() ?? null}
          onGoLive={() => captureRef.current?.goLive()}
          onBufferExceeded={handleBufferExceeded}
        />
      )}

      {/* Altyazı bindirmesi geri sarılan bölümde de çalışabiliyor:
          tileCapture'ın playingDate()'i (yukarıda) rewindStart +
          video.currentTime ile geçmişteki GERÇEK anı veriyor — canlı
          altyazı satırları zaten mutlak zaman damgasıyla eşleşiyor,
          kaynağın canlı mı geri sarılmış mı olduğu SubtitleOverlay için
          fark etmiyor. captureRef DEĞİL tileCapture kullanılmalı: geri
          sarılmışken captureRef.current HlsPlayer'ın unmount'uyla null
          kalıyor. Geri sarmadaki altyazı üretimde hiç denenmedi, bu yüzden
          rewindUrl varken DVR_ALTYAZI_ACIK'a bağlı (canlıda her zaman açık,
          bu zaten doğrulanmış). */}
      {subtitleLang !== 'kapali' && (!rewindUrl || dvrAltyaziAcikMi()) && (
        <SubtitleOverlay
          channelId={channel.id}
          capture={tileCapture}
          language={subtitleLang}
          // Denetim çubuğu açıkken altyazı yukarı kalkıyor; aksi halde çubuğun
          // arkasında kalıp okunmuyordu.
          className={showControls && !compact ? 'pb-20' : undefined}
        />
      )}

      {/* Karoya tıklamak büyütür/küçültür. Tam kaplıyor: denetim katmanı
          pointer-events-none olduğu için ortadaki boş alana yapılan tıklama
          buraya iniyor, düğmelerin üstüne yapılan inmiyor. Eskiden yalnızca
          üst şeritti çünkü yerleşik denetimler tıklamayı yutuyordu. */}
      <button
        type="button"
        className="absolute inset-0 cursor-pointer"
        onClick={onToggleExpand}
        aria-label={expanded ? 'Küçült' : 'Büyük ekranda aç'}
      />

      <div
        className={cn(
          'pointer-events-none absolute inset-x-0 top-0 flex items-start justify-between gap-2 bg-gradient-to-b from-black/70 to-transparent p-2',
          compact && 'hidden',
        )}
      >
        <div className="flex min-w-0 items-center gap-2">
          <span className="truncate text-xs font-medium text-white">{channel.name}</span>
          {/* CANLI rozeti yalnızca canlı akışta: geri sarılmış bölüm canlı
              değil ve orada göstermek doğrudan yanlış bilgi olurdu. */}
          {!rewindUrl && (
            <span className="flex shrink-0 items-center gap-1 rounded bg-status-live px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-white">
              Canlı
            </span>
          )}
        </div>
        {/* Dugmeler HER ZAMAN gorunur. Onceden yalnizca fare uzerine gelince
            beliriyordu: kayit ve kare yakalama, varligi kesfedilmesi gereken
            gizli ozellikler degil -- kullanici karsisinda durani kullanir.
            Ustteki gradyan okunurlugu zaten sagliyor. */}
        <div data-tour="karo-eylemleri" className="pointer-events-auto flex gap-1">
          <TileActions
            channel={channel}
            capture={tileCapture}
            recording={recording}
            rewound={rewindUrl !== null}
            pendingClipStart={pendingClipStart}
            onPendingClipStartChange={setPendingClipStart}
            onRecordingChanged={onRecordingChanged}
          />
          {/* Altyazı dili karo başına: mozaikte farklı kanallar farklı dilde
              izlenebilmeli. Tek bir genel ayar bunu imkânsız kılardı. */}
          <select
            aria-label="Altyazı"
            title="Altyazı dili"
            className="h-7 rounded-md border bg-secondary px-1.5 text-xs text-secondary-foreground"
            value={subtitleLang}
            onChange={(e) => {
              const yeniDil = e.target.value
              setSubtitleLang(yeniDil)
              // Kullanici davranisi denetim izi icin -- altyazinin kendi
              // akisini etkilemez, bu yuzden hata sessizce yutuluyor.
              if (yeniDil !== 'kapali') void subtitlesApi.dilDegisti(channel.id, yeniDil).catch(() => {})
            }}
            onClick={(e) => e.stopPropagation()}
          >
            {subtitleLangs().map((l) => (
              <option key={l.kod} value={l.kod}>
                {l.ad}
              </option>
            ))}
          </select>
          {qualities.length > 1 && (
            <div className="relative">
              <select
                aria-label="Çözünürlük"
                className="h-7 rounded-md border bg-secondary px-1.5 pr-5 text-xs text-secondary-foreground"
                value={selected.suffix}
                onChange={(e) => onQuality(e.target.value)}
                onClick={(e) => e.stopPropagation()}
              >
                {qualities.map((q) => (
                  <option key={q.suffix} value={q.suffix}>
                    {q.label}
                  </option>
                ))}
              </select>
              <SettingsIcon className="pointer-events-none absolute right-1 top-1.5 size-3 opacity-60" />
            </div>
          )}
          <Button
            variant="secondary"
            size="icon"
            className="size-7"
            title={hasAudio ? 'Sesi kapat' : 'Sesi bu kanala ver'}
            onClick={onAudio}
          >
            {hasAudio ? <Volume2Icon /> : <VolumeXIcon />}
          </Button>
          <Button
            variant="secondary"
            size="icon"
            className="size-7"
            title="Kapat"
            onClick={onClose}
          >
            <XIcon />
          </Button>
        </div>
      </div>

      {/* DVR çubuğu — kayıtlı aralıklarda serbest gezmeyi sağlar. Denetim
          çubuğundaki ±10 sn'den ayrı: o yalnızca atlanabilir aralıkta
          gezinir, bu kaynağı DVR kaydına çevirir. Çubuğun ÜSTÜNDE
          duruyor, yoksa üst üste binerdi. */}
      {showControls && !compact && (
        <div className="pointer-events-none absolute inset-x-0 bottom-24 flex justify-center">
          <div className="rounded-full bg-black/70 px-2 py-1">
            <LiveRewind
              ref={liveRewindRef}
              channel={channel}
              rewound={rewindUrl !== null}
              onRewind={onRewind}
              onLive={backToLive}
            />
          </div>
        </div>
      )}

      {/* Ses göstergesi. Denetim çubuğu varken gizli: çubukta zaten sessize
          alma düğmesi var ve rozet onun arkasında kalıyordu. */}
      {hasAudio && !compact && !showControls && (
        <div className="pointer-events-none absolute bottom-2 left-2">
          <Badge variant="secondary" className="gap-1">
            <Volume2Icon className="size-3" />
            ses
          </Badge>
        </div>
      )}
    </div>
  )
}
