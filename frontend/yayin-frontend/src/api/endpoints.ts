import { api } from './client'
import type {
  Capacity,
  ChannelDto,
  ChannelRequest,
  ActiveRecordingDto,
  ClipDto,
  ClipLinks,
  ClipOrigin,
  CreateClipRequest,
  CreateUserRequest,
  CreateVideoRequest,
  RadioDto,
  RadioRequest,
  QuotaUsage,
  RestoreResult,
  Role,
  ScreenshotDto,
  SyncResultDto,
  TimelineSpan,
  TokenResponse,
  UpdateVideoRequest,
  UploadTicket,
  UserDto,
  VideoDto,
  VideoLinks,
} from './types'

/** Backend uçlarının tek tanım yeri; bileşenler ham yol string'i taşımaz. */

export const authApi = {
  login: (username: string, password: string) =>
    api.post<TokenResponse>('/api/auth/login', { username, password }, { anonymous: true }),

  logout: (refreshToken: string) =>
    api.post<void>('/api/auth/logout', { refreshToken }, { anonymous: true }),
}

export const profileApi = {
  me: () => api.get<UserDto>('/api/users/me'),

  changePassword: (currentPassword: string, newPassword: string) =>
    api.put<void>('/api/users/me/password', { currentPassword, newPassword }),

  /** Klip, kayıt, ekran görüntüsü ve video toplamı. */
  quota: () => api.get<QuotaUsage>('/api/users/me/kota'),
}

export const adminUsersApi = {
  list: (search?: string, first = 0, max = 50) => {
    const params = new URLSearchParams({ first: String(first), max: String(max) })
    if (search) params.set('search', search)
    return api.get<UserDto[]>(`/api/admin/users?${params}`)
  },

  create: (request: CreateUserRequest) => api.post<UserDto>('/api/admin/users', request),

  changeRole: (id: string, role: Role) =>
    api.put<UserDto>(`/api/admin/users/${id}/role`, { role }),

  resetPassword: (id: string, newPassword: string, temporary: boolean) =>
    api.put<void>(`/api/admin/users/${id}/password`, { newPassword, temporary }),

  remove: (id: string) => api.delete<void>(`/api/admin/users/${id}`),

  sync: () => api.post<SyncResultDto>('/api/admin/users/sync'),
}

export const channelsApi = {
  list: () => api.get<ChannelDto[]>('/api/channels'),

  create: (request: ChannelRequest) => api.post<ChannelDto>('/api/channels', request),

  update: (id: string, request: ChannelRequest) =>
    api.put<ChannelDto>(`/api/channels/${id}`, request),

  remove: (id: string) => api.delete<void>(`/api/channels/${id}`),

  capacity: () => api.get<Capacity>('/api/channels/capacity'),

  /** Aktif kanalları MediaMTX'e yeniden yazar; MediaMTX bağımsız yeniden başlatıldığında gerekir. */
  restore: () => api.post<RestoreResult>('/api/channels/restore'),
}

export const radiosApi = {
  list: () => api.get<RadioDto[]>('/api/radios'),

  create: (request: RadioRequest) => api.post<RadioDto>('/api/radios', request),

  update: (id: string, request: RadioRequest) =>
    api.put<RadioDto>(`/api/radios/${id}`, request),

  remove: (id: string) => api.delete<void>(`/api/radios/${id}`),

  /** Kanal kapasitesinden ayrı sayaç: radyonun maliyeti aynı ölçekte değil. */
  capacity: () => api.get<Capacity>('/api/radios/capacity'),

  restore: () => api.post<RestoreResult>('/api/radios/restore'),
}

export const videosApi = {
  list: (query?: string, offset = 0, limit = 50) => {
    const params = new URLSearchParams({ offset: String(offset), limit: String(limit) })
    if (query) params.set('q', query)
    return api.get<VideoDto[]>(`/api/videos?${params}`)
  },

  get: (id: string) => api.get<VideoDto>(`/api/videos/${id}`),

  /** İzleme ve indirme adresleri; yalnızca oynatma anında isteniyor. */
  links: (id: string) => api.get<VideoLinks>(`/api/videos/${id}/links`),

  /**
   * Yüklemeyi başlatır. Dosya bu istekte GİTMEZ — yanıttaki imzalı adrese
   * ayrıca PUT edilir (bkz. uploadToStorage).
   */
  startUpload: (request: CreateVideoRequest) =>
    api.post<UploadTicket>('/api/videos', request),

  completeUpload: (id: string) => api.post<VideoDto>(`/api/videos/${id}/tamamlandi`, {}),

  update: (id: string, request: UpdateVideoRequest) =>
    api.put<VideoDto>(`/api/videos/${id}`, request),

  /**
   * Küçük resim olarak görsel yükler. Video dosyasının aksine bu BACKEND
   * ÜZERİNDEN gidiyor: birkaç yüz kilobayt için imzalı adres dansı kurmak
   * gereksiz olurdu.
   */
  uploadThumbnail: (id: string, file: File) => {
    const form = new FormData()
    form.append('dosya', file)
    return api.postForm<VideoDto>(`/api/videos/${id}/kucukresim`, form)
  },

  remove: (id: string) => api.delete<void>(`/api/videos/${id}`),
}

export const recordingsApi = {
  start: (channelId: string) =>
    api.post<ActiveRecordingDto>(`/api/channels/${channelId}/clips/kayit`, {}),

  /** Durdurma klip işi açar; yanıt 202 ve dosya henüz yoktur. */
  stop: (channelId: string) =>
    api.delete<ClipDto>(`/api/channels/${channelId}/clips/kayit`),

  active: () => api.get<ActiveRecordingDto[]>('/api/clips/kayitlar/devam-eden'),
}

export const screenshotsApi = {
  gallery: (channelId?: string, offset = 0, limit = 60) => {
    const params = new URLSearchParams({ offset: String(offset), limit: String(limit) })
    if (channelId) params.set('channelId', channelId)
    return api.get<ScreenshotDto[]>(`/api/screenshots?${params}`)
  },

  /**
   * Kare TARAYICIDA yakalanıp buraya yükleniyor. capturedAt, kullanıcının
   * izlediği ANI bildiriyor — HLS gecikmesi nedeniyle "şu an"dan farklı.
   */
  capture: (channelId: string, blob: Blob, capturedAt: Date,
            width: number, height: number, note?: string) => {
    const form = new FormData()
    form.append('dosya', blob, 'kare.jpg')
    form.append('capturedAt', capturedAt.toISOString())
    form.append('width', String(width))
    form.append('height', String(height))
    if (note) form.append('note', note)
    return api.postForm<ScreenshotDto>(`/api/screenshots/${channelId}`, form)
  },

  remove: (id: string) => api.delete<void>(`/api/screenshots/${id}`),
}

export const dvrApi = {
  /** Verilen pencerede kayıt bulunan aralıklar. */
  timeline: (channelId: string, from: Date, to: Date) =>
    api.get<TimelineSpan[]>(
      `/api/channels/${channelId}/dvr/timeline` +
        `?from=${encodeURIComponent(from.toISOString())}` +
        `&to=${encodeURIComponent(to.toISOString())}`,
    ),

  /**
   * Geçmişten oynatma adresi. Token gerektirdiği için <video src> ile
   * doğrudan kullanılamaz; fetch ile alınıp blob'a çevrilmeli.
   */
  streamUrl: (channelId: string, start: Date, durationSeconds: number) =>
    `/api/channels/${channelId}/dvr/stream` +
    `?start=${encodeURIComponent(start.toISOString())}&duration=${durationSeconds}`,
}

export const clipsApi = {
  create: (channelId: string, request: CreateClipRequest) =>
    api.post<ClipDto>(`/api/channels/${channelId}/clips`, request),

  list: (channelId?: string, origin?: ClipOrigin) => {
    const params = new URLSearchParams()
    if (channelId) params.set('channelId', channelId)
    if (origin) params.set('origin', origin)
    const q = params.toString()
    return api.get<ClipDto[]>(`/api/clips${q ? `?${q}` : ''}`)
  },

  get: (id: string) => api.get<ClipDto>(`/api/clips/${id}`),

  remove: (id: string) => api.delete<void>(`/api/clips/${id}`),

  /**
   * İzleme ve indirme adresleri. Yönlendirme yerine JSON: tarayıcı CORS
   * nedeniyle yönlendirme yanıtındaki Location başlığını okuyamıyor.
   */
  links: (id: string) => api.get<ClipLinks>(`/api/clips/${id}/links`),
}
