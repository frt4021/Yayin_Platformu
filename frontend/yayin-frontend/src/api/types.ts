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
  dvrRendition: string
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
  dvrRendition: string
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

/** Backend'in tüm hatalarda döndüğü tek format. */
export interface ErrorResponse {
  timestamp: string
  status: number
  error: string
  message: string
  path: string
  fieldErrors: { field: string; message: string }[]
}
