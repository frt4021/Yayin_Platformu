/** Backend DTO'larının TypeScript karşılıkları. */

export const ROLES = ['Yönetici', 'Moderatör', 'İzleyici'] as const
export type Role = (typeof ROLES)[number]

export interface TokenResponse {
  access_token: string
  expires_in: number
  refresh_token: string
  refresh_expires_in: number
  token_type: string
}

export const ETKINLIK_TURLERI = [
  'GIRIS',
  'GIRIS_BASARISIZ',
  'CIKIS',
  'IZLEME_BASLADI',
  'IZLEME_BITTI',
  'DINLEME_BASLADI',
  'DINLEME_BITTI',
  'ALTYAZI_DIL_DEGISTI',
  'KALITE_DEGISTI',
  'DVR_GERI_SARILDI',
  'KLIP_OLUSTURULDU',
  'KAYIT_BASLADI',
  'KAYIT_DURDU',
  'KANAL_EKLENDI',
  'KANAL_SILINDI',
  'RADYO_EKLENDI',
  'RADYO_SILINDI',
  'KULLANICI_EKLENDI',
  'KULLANICI_SILINDI',
  'KULLANICI_ROLU_DEGISTI',
  'VIDEO_YUKLENDI',
  'VIDEO_SILINDI',
  'OYNATMA_HATASI',
  'OYNATMA_TAKILMA',
] as const
export type EtkinlikTuru = (typeof ETKINLIK_TURLERI)[number]

export interface EtkinlikDto {
  id: string
  kullaniciId: string | null
  kullaniciAdi: string | null
  tur: EtkinlikTuru
  hedefTuru: string | null
  hedefId: string | null
  /** hedefId'nin çözümlenmiş adı (kanal/radyo/video adı vb.); hedef yoksa null, silinmişse "Silinmiş …". */
  hedefAdi: string | null
  detay: Record<string, unknown>
  olusturmaZamani: string
}

export interface EtkinlikSayfasiDto {
  items: EtkinlikDto[]
  total: number
  first: number
  max: number
}

/** Bant genişliği MediaMTX metrik entegrasyonu olmadan ölçülmüyor — null. */
export interface CanliDurumDto {
  esZamanliIzleyici: number
  esZamanliDinleyici: number
  aktifDvrKaydi: number
  anlikTrafikMbps: number | null
}

export interface TopEtiketDto {
  id: string
  ad: string
  sayi: number
}

export interface IcerikPerformansiDto {
  enCokIzlenenKanallar: TopEtiketDto[]
  enCokDinlenenRadyolar: TopEtiketDto[]
  enCokKaydedilenYayinlar: TopEtiketDto[]
}

export interface KullaniciKullanimDto {
  kullaniciAdi: string
  toplamBayt: number
  yuzde: number
}

export interface DepolamaDto {
  enYuksekKullanicilar: KullaniciKullanimDto[]
  gelecek24SaatPlanliKayit: number
  toplamDvrBoyutBayt: number
}

/** yayinKopmaOrani: hiç izleme/dinleme başlangıcı yoksa null (oran anlamsız). */
export interface TeknikDto {
  basarisizPlanliKayit: number
  videoIslemeHatasi: number
  yayinKopmaOrani: number | null
}

export interface GenelAktiviteDto {
  dau: number
  mau: number
  saatBazliGiris: Record<string, number>
  ortalamaIzlemeBaslangici24s: number
}

export interface OynatmaOzeti {
  hataSayisi: number
  takilmaSayisi: number
  sonMesaj: string | null
}

export interface BilesenSaglikDurumu {
  bilesen: string
  saglikli: boolean
  detay: string
}

export interface SistemSagligiOzetDto {
  bilesenler: BilesenSaglikDurumu[]
  sonEtkinlikler: EtkinlikDto[]
}

/**
 * BilesenSaglikDurumu'nun (yalnızca erişilebilir mi) aksine gerçek sayısal
 * detay — Prometheus'tan okunuyor, ulaşılamıyorsa/veri yoksa alan null.
 */
export interface ServisMetrikleriDto {
  tritonIstekSayisi5dk: number | null
  tritonOrtalamaGecikmeMs: number | null
  tritonGpuBellekBayt: number | null
  /** Model/dil bazlı ortalama gecikme (ms) — anahtar: whisper, marian_en_tr, marian_en_de, marian_en_ru. */
  tritonModelGecikmeMs: Record<string, number>
  postgresAktifBaglanti: number | null
  postgresBoyutBayt: number | null
  postgresCommitOrani5dk: number | null
  redisBagliIstemci: number | null
  redisBellekBayt: number | null
  redisKomutOrani5dk: number | null
  minioKullanilanBayt: number | null
  minioToplamBayt: number | null
  mediaMtxAktifPath: number | null
  mediaMtxAktifHlsMuxer: number | null
}

export interface HedefIzlemeOzetiDto {
  id: string
  ad: string
  oturumSayisi: number
  toplamSureMs: number
}

/** {@code TopEtiketDto}'nun aksine gerçek bir id yok — isimle gruplandı. */
export interface AdSayiDto {
  ad: string
  sayi: number
}

/** kullaniciAdi: yerel kayıt yoksa (hiç giriş yapmamış) null. */
export interface KullaniciAktiviteDto {
  kullaniciAdi: string | null
  videoYuklemeSayisi: number
  klipSayisi: number
  toplamIzlemeSuresiMs: number
  izlenenKanallar: HedefIzlemeOzetiDto[]
  dinlenenRadyolar: HedefIzlemeOzetiDto[]
  klipAlinanKanallar: AdSayiDto[]
  manuelKayitAlinanKanallar: TopEtiketDto[]
  geriSarilanKanallar: TopEtiketDto[]
  sonEtkinlikler: EtkinlikDto[]
  sonGiris: string | null
}

export interface UserDto {
  /** Keycloak kullanıcı id'si (token'daki sub). Yerel users.id dışarı açılmıyor. */
  id: string
  username: string
  email: string | null
  firstName: string | null
  lastName: string | null
  enabled: boolean
  role: Role
  createdAt: string | null
}

export interface CreateUserRequest {
  username: string
  email: string
  firstName?: string
  lastName?: string
  password: string
  temporary: boolean
  role: Role
}

export interface SyncResultDto {
  created: string[]
  updated: string[]
  /** Keycloak'ta artık olmayan ama yerelde duran kayıtlar; otomatik silinmez. */
  orphaned: string[]
}

export interface ChannelDto {
  id: string
  name: string
  sourceUrl: string
  mediamtxPath: string
  active: boolean
  /** Geriye sarma kaydı açık mı. Yayında olmaktan bağımsız. */
  dvrEnabled: boolean
  /** Çözünürlük merdiveni: "720p|1280x720|1500k,480p|854x480|800k". Boş = transcode yok. */
  renditions: string
  /** DVR kaydının alındığı rendition adı; boş ise kaynak çözünürlüğü. */
  /**
   * MediaMTX'e gerçekte yazılan adres. Master playlist'ten bir varyant
   * seçildiyse dolu — girilen adresle aynıysa null.
   */
  resolvedSourceUrl: string | null
  /** Kaynağın tespit edilen çözünürlüğü; HLS olmayan kaynaklarda null. */
  sourceWidth: number | null
  sourceHeight: number | null
  hlsUrl: string
  /** MediaMTX'ten anlık durum; sunucuya ulaşılamadıysa null. */
  streaming: boolean | null
  viewers: number | null
  createdBy: string | null
  createdAt: string | null
}

/**
 * Kanal oluşturma/güncelleme gövdesi. İkisi aynı alanları alıyor: güncelleme
 * PUT semantiğinde, yani gönderilen hal kanalın yeni hali oluyor.
 *
 * @param mediamtxPath MediaMTX'teki path adı; HLS adresi bundan türer.
 *                     Backend harf, rakam, alt çizgi ve tire ile sınırlıyor.
 * @param active       true ise kanal MediaMTX'e yazılır ve yayın çekilmeye başlar.
 */
export interface ChannelRequest {
  name: string
  sourceUrl: string
  mediamtxPath: string
  active: boolean
  dvrEnabled: boolean
  renditions: string
}

/** POST /api/channels/restore yanıtı. */
export interface RestoreResult {
  restored: number
}

/**
 * Kanal kapasitesi. Sınır donanım/bant genişliği kaynaklı; aşıldığında
 * MediaMTX tüm kanallarda birden bozulmaya başladığı için backend
 * yeni aktif kanalı reddeder.
 */
export interface Capacity {
  active: number
  max: number
}

export const RADIO_SOURCE_KINDS = ['DOGRUDAN', 'KOPRU'] as const
export type RadioSourceKind = (typeof RADIO_SOURCE_KINDS)[number]

export interface RadioDto {
  id: string
  name: string
  sourceUrl: string
  /**
   * DOGRUDAN: adres MediaMTX'e kaynak olarak verilir (HLS/RTSP/RTMP/SRT/UDP).
   * KOPRU: MediaMTX içinde bir ffmpeg süreci adresi çekip AAC'ye kodlar.
   *
   * <p>Tahmin edilmiyor, kullanıcı seçiyor: MediaMTX http(s) adreslerini HLS
   * sayıyor ve düz bir Icecast MP3 adresini hatasız kabul edip sessizce hiç
   * yayına almıyor.
   */
  sourceKind: RadioSourceKind
  mediamtxPath: string
  /** Yalnızca KOPRU modunda anlamlı: üretilen AAC bit hızı. */
  bitrate: string
  active: boolean
  logoUrl: string | null
  sortOrder: number
  /** Ses-only HLS manifesti; hls.js ile <audio> elementine bağlanır. */
  hlsUrl: string
  /** MediaMTX'ten anlık durum; sunucuya ulaşılamadıysa null. */
  streaming: boolean | null
  listeners: number | null
  createdBy: string | null
  createdAt: string | null
}

/** Radyo oluşturma/güncelleme gövdesi; ikisi de PUT semantiğinde tam nesne alır. */
export interface RadioRequest {
  name: string
  sourceUrl: string
  sourceKind: RadioSourceKind
  mediamtxPath: string
  bitrate: string
  active: boolean
  logoUrl: string
  sortOrder: number
}

/** Kayıt bulunan bir zaman aralığı. Boşluklar ayrı aralık olarak gelir. */
export interface TimelineSpan {
  start: string
  end: string
}

export const CLIP_STATUS = ['BEKLIYOR', 'ISLENIYOR', 'HAZIR', 'HATA'] as const
export type ClipStatus = (typeof CLIP_STATUS)[number]

/** Hazır bir WebVTT altyazı parçası. Görünen ad yok — SubtitleOverlay.subtitleLangs()'tan çıkarılıyor. */
export interface SubtitleTrackDto {
  lang: string
  url: string
}

export interface ClipDto {
  id: string
  /** Kanal silinip bağ koparıldıysa null — klip yine de izlenebilir. */
  channelId: string | null
  channelName: string
  start: string
  end: string
  durationSeconds: number
  status: ClipStatus
  /** Klibin nasıl istendiği: aralık seçimi mi, manuel kayıt mı. */
  origin: ClipOrigin
  /** Yalnızca HAZIR durumunda dolu. */
  sizeBytes: number | null
  /** Yalnızca HATA durumunda dolu. */
  error: string | null
  requestedBy: string
  createdAt: string
  completedAt: string | null
  /** WebVTT altyazısı üretilen diller; boşsa altyazı yok. */
  subtitleLangs: string[]
}

/** Süreli imzalı adresler; dosya doğrudan nesne depolamasından gelir. */
export interface ClipLinks {
  /** <video src> ile oynatılabilir. */
  stream: string
  /** Tarayıcıyı dosyayı kaydetmeye zorlar. */
  download: string
  fileName: string
  subtitles: SubtitleTrackDto[]
}

export interface CreateClipRequest {
  start: string
  end: string
}

export const VIDEO_STATUS = ['YUKLENIYOR', 'ISLENIYOR', 'HAZIR', 'HATA'] as const
export type VideoStatus = (typeof VIDEO_STATUS)[number]

export interface VideoDto {
  id: string
  title: string
  description: string | null
  originalFilename: string | null
  contentType: string | null
  /** İşçi tarafından doğrulanmış gerçek boyut; işlenene kadar null. */
  sizeBytes: number | null
  durationSeconds: number | null
  width: number | null
  height: number | null
  status: VideoStatus
  /** Yalnızca HATA durumunda dolu. */
  error: string | null
  /**
   * Süreli imzalı küçük resim adresi. Listede geliyor çünkü ızgaradaki her
   * kart için ayrı bir istek atmak N+1 çağrı olurdu; izleme adresi ise
   * yalnızca oynatılırken gerektiği için ayrı uçta.
   */
  thumbnailUrl: string | null
  /**
   * Kısa önizleme klibinin imzalı adresi; üretilmediyse null. Küçük resimle
   * aynı gerekçeyle listede: fare karta geldiği anda oynaması gerekiyor.
   */
  previewUrl: string | null
  /**
   * Küçük resmi kullanıcı mı yükledi. thumbnailAtSeconds ile birlikte üç
   * durumu ayırıyor: ikisi de boşsa otomatik kare, saniye doluysa kullanıcının
   * seçtiği kare, bu bayrak açıksa yüklenen görsel.
   */
  thumbnailIsUpload: boolean
  thumbnailAtSeconds: number | null
  /** İzlenme sayısı — "Oynat" düğmesine basıldığında artar. */
  viewCount: number
  uploadedBy: string | null
  createdAt: string | null
  completedAt: string | null
  /** WebVTT altyazısı üretilen diller; boşsa altyazı yok (bkz. videos.subtitle-enabled). */
  subtitleLangs: string[]
}

export interface CreateVideoRequest {
  title: string
  description: string
  fileName: string
  contentType: string
  sizeBytes: number
}

export interface UpdateVideoRequest {
  title: string
  description: string
  /** null gönderilirse mevcut küçük resme dokunulmaz. */
  thumbnailAtSeconds: number | null
}

/** Yükleme izni: dosyanın doğrudan nesne depolamasına yazılması için gerekenler. */
export interface UploadTicket {
  videoId: string
  /** İmzalı PUT adresi. Bu isteğe Authorization başlığı EKLENMEMELİ. */
  uploadUrl: string
  contentType: string | null
  expiresAt: string
}

export interface VideoLinks {
  stream: string
  download: string
  thumbnail: string | null
  fileName: string
  subtitles: SubtitleTrackDto[]
}

export const CLIP_ORIGIN = ['ARALIK', 'MANUEL_KAYIT'] as const
export type ClipOrigin = (typeof CLIP_ORIGIN)[number]

/** Devam eden manuel kayıt. */
export interface ActiveRecordingDto {
  channelId: string
  channelName: string
  startedAt: string
  /** Üst sınır; arayüz "şu kadar kaldı" gösterip otomatik duracağını söyler. */
  maxMinutes: number
}

export interface ScreenshotDto {
  id: string
  /** Kanal silinip bağ koparıldıysa null — görüntü yine de görüntülenebilir. */
  channelId: string | null
  channelName: string
  /** Karenin ait olduğu YAYIN anı — createdAt kaydın oluşturulduğu an. */
  capturedAt: string
  width: number | null
  height: number | null
  sizeBytes: number
  note: string | null
  /** İmzalı adresler listede geliyor; ızgarada kart başına istek olmasın diye. */
  viewUrl: string
  downloadUrl: string
  fileName: string
  capturedBy: string
  createdAt: string
}

/** Depolama kullanımı. quotaBytes 0 ise sınırsız. */
export interface QuotaUsage {
  clipBytes: number
  screenshotBytes: number
  videoBytes: number
  totalBytes: number
  quotaBytes: number
  unlimited: boolean
  percentUsed: number
  remainingBytes: number
}

/** Backend'in tüm hatalarda döndüğü tek format. */
export interface ErrorResponse {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  fieldErrors: { field: string; message: string }[]
}

/** Planlı bir kayıt emrinin yaşam döngüsü. */
export type ScheduledStatus =
  | 'BEKLIYOR'
  | 'KAYITTA'
  | 'TAMAMLANDI'
  | 'BASARISIZ'
  | 'IPTAL'

export interface ScheduledRecordingDto {
  id: string
  channelId: string
  channelName: string
  baslangic: string
  bitis: string
  durationSeconds: number
  durum: ScheduledStatus
  /** Üretilen klip; henüz üretilmediyse null. */
  clipId: string | null
  hata: string | null
  /** Kanalın geriye sarması bu emir için mi açıldı. */
  dvrBizden: boolean
  requestedBy: string
  createdAt: string
}

export interface CreateScheduledRecordingRequest {
  baslangic: string
  bitis: string
}

/**
 * Kayıt durdurma sonucu.
 *
 * <p>Durdurma <b>her koşulda başarılı olur</b>; klip açılamazsa bu ayrı bir
 * hata değil, {@link error} dolu döner. Bu yüzden yanıt bir ClipDto değil:
 * "durdu ama klip yok" durumu anlatılabilmeli.
 */
export interface StopRecordingResult {
  start: string
  end: string
  /** Klip işi açıldıysa; açılamadıysa null. */
  clip: ClipDto | null
  /** Klip açılamadıysa sebebi. Kayıt yine de durmuştur. */
  error: string | null
}

/**
 * Bir konuşma bölütünün altyazısı.
 *
 * Zaman damgaları MUTLAK: oynatıcı kendi `playingDate()` değeriyle
 * eşleştiriyor. Göreli süre gönderilseydi izleyicinin yayında nerede olduğu
 * bilinmediği için eşleşme mümkün olmazdı.
 */
export interface SubtitleDto {
  id: string
  baslangic: string
  bitis: string
  /** Whisper'ın tespit ettiği kaynak dil, örn. 'tr'. */
  kaynakDil: string | null
  guven: number | null
  /** Dil kodundan metne. `en` her zaman var — pivot dil. */
  metinler: Record<string, string>
  /** Bölüt üst sınır aşıldığı için kesildi mi — cümle ortasında olabilir. */
  kesik: boolean
}

/** Kanal silinmeden önce neyin gideceğinin dökümü. */
export interface ChannelDeletionSummary {
  channelName: string
  clipCount: number
  screenshotCount: number
  dvrSegmentCount: number
  /** DVR kaydının toplam süresi. Segment sayısı kullanıcıya bir şey ifade etmiyor, süre ediyor. */
  dvrHours: number
  dvrBytes: number
  clipBytes: number
  /** Yayındaki bir kanalı silmek büyük ihtimalle kazadır; ayrıca uyarılıyor. */
  streaming: boolean
}

export const SISTEM_LOG_SEVIYE = ['HATA', 'UYARI', 'BASARI', 'BILGI'] as const
export type SistemLogSeviye = (typeof SISTEM_LOG_SEVIYE)[number]

/**
 * Bir konteyner log satırının Türkçeye yorumlanmış hali. Yalnızca bilinen
 * bir örüntüye uyan (ya da genel hata/uyarı sinyali taşıyan) satırlar
 * gelir — rutin gürültü backend'de zaten süzülmüş.
 */
export interface SistemLogDto {
  zaman: string
  servis: string
  seviye: SistemLogSeviye
  mesaj: string
  /** Orijinal log satırı — katlanabilir detayda gösterilir. */
  hamMesaj: string
}
