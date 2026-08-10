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

export interface ClipDto {
  id: string
  channelId: string
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
}

/** Süreli imzalı adresler; dosya doğrudan nesne depolamasından gelir. */
export interface ClipLinks {
  /** <video src> ile oynatılabilir. */
  stream: string
  /** Tarayıcıyı dosyayı kaydetmeye zorlar. */
  download: string
  fileName: string
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
  uploadedBy: string | null
  createdAt: string | null
  completedAt: string | null
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
  channelId: string
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
