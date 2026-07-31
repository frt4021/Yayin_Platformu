import { api } from './client'
import type {
  Capacity,
  ChannelDto,
  ChannelRequest,
  ClipDto,
  ClipLinks,
  CreateClipRequest,
  CreateUserRequest,
  RestoreResult,
  Role,
  SyncResultDto,
  TimelineSpan,
  TokenResponse,
  UserDto,
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
