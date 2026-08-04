import type { ChannelDto } from '@/api/types'

export interface Quality {
  /** Görünen ad: "Kaynak", "720p" … */
  label: string
  /** MediaMTX path son eki; kaynak için boş. */
  suffix: string
  hlsUrl: string
}

/**
 * Kanalın izlenebilir kalite seçenekleri.
 *
 * <p>MediaMTX rendition'ları tek bir master playlist'te birleştirmiyor —
 * her biri ayrı bir path ve ayrı bir adres. Seçim bu yüzden arayüzde
 * yapılıyor; tarayıcı kendi başına kalite değiştiremez.
 */
export function qualitiesOf(channel: ChannelDto): Quality[] {
  const source: Quality = { label: 'Kaynak', suffix: '', hlsUrl: channel.hlsUrl }
  if (!channel.renditions) return [source]

  const ladder = channel.renditions
    .split(',')
    .map((entry) => entry.split('|')[0])
    .filter(Boolean)
    .map((suffix) => ({
      label: suffix,
      suffix,
      // hlsUrl ".../<path>/index.m3u8" biçiminde; path'e son ek ekleniyor.
      hlsUrl: channel.hlsUrl.replace(
        /\/([^/]+)\/index\.m3u8$/,
        (_m, path) => `/${path}_${suffix}/index.m3u8`,
      ),
    }))

  return [source, ...ladder]
}
