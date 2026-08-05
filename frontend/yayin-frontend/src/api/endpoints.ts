import { api } from './client'
import type {
  Capacity,
  ChannelDto,
  ChannelRequest,
  ClipDto,
  ClipLinks,
  CreateClipRequest,
  CreateUserRequest,
  CreateVideoRequest,
  RadioDto,
  RadioRequest,
  RestoreResult,
  Role,
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

  list: (channelId?: string) =>
    api.get<ClipDto[]>(`/api/clips${channelId ? `?channelId=${channelId}` : ''}`),

  get: (id: string) => api.get<ClipDto>(`/api/clips/${id}`),

  remove: (id: string) => api.delete<void>(`/api/clips/${id}`),

  /**
   * İzleme ve indirme adresleri. Yönlendirme yerine JSON: tarayıcı CORS
   * nedeniyle yönlendirme yanıtındaki Location başlığını okuyamıyor.
   */
  links: (id: string) => api.get<ClipLinks>(`/api/clips/${id}/links`),
}
