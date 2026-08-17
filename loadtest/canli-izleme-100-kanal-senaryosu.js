// 100 kullanıcı / 100 kanal ölçekleme testi — GERÇEK GPU tavanını ölçmek
// için. Önceki senaryolardan (canli-izleme-*.js) farkı: kanal listesini
// HARDCODE ETMEK yerine test başında (setup()) API'den çeker — böylece
// "liste eski, kanal path'i değişti" sınıfı hatayı yapısal olarak ortadan
// kaldırır ve o an kaç kanal gerçekten aktifse hepsine dağıtır.
//
// ÖN KOŞUL — 100 kanala ulaşmak için İKİ BAĞIMSIZ uygulama sınırı aşılmalı
// (ikisi de docs/teknik-dokuman.md §8'de ölçüldü):
//   1. CHANNELS_MAX_ACTIVE (varsayılan 16) — aynı anda YAYINDA olabilecek
//      kanal sayısı, ChannelService'te 409 olarak uygulanıyor.
//   2. VAD_MAX_CHANNELS (varsayılan 20) — aynı anda ALTYAZI üretilecek
//      kanal sayısı, VadService'te. Yalnızca #1'i yükseltmek yeterli
//      DEĞİL: fazla kanallar yayınlanır ama altyazı almaz.
// İkisini de `.env`'de yükseltip backend + video-worker'ı yeniden başlatın,
// testten sonra 16/20'ye geri döndürüp tekrar yeniden başlatmayı unutmayın.
//
// ÇALIŞTIRMA (k6 kurulu olmalı):
//
//   BASE_URL=http://localhost:3000 \
//   USERNAME=admin1 PASSWORD=12345678 \
//   VUS=100 \
//   k6 run loadtest/canli-izleme-100-kanal-senaryosu.js

import http from 'k6/http'
import ws from 'k6/ws'
import { check, sleep } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000'
const USERNAME = __ENV.USERNAME || 'admin1'
const PASSWORD = __ENV.PASSWORD || '12345678'
const MANIFEST_POLL_SECONDS = 3
const TEST_DURATION_SECONDS = 60

export const options = {
  scenarios: {
    izleyici: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 100),
      duration: `${TEST_DURATION_SECONDS}s`,
    },
  },
}

function girisYap() {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } },
  )
  check(res, { 'giriş başarılı': (r) => r.status === 200 })
  if (res.status !== 200) return null
  return res.json('access_token')
}

// setup() TEK SEFERLİK çalışır — her VU'nun ayrı ayrı kanal listesi
// çekmesini önler, aynı zamanda test anında GERÇEKTEN var olan kanalları
// kullanmayı garanti eder.
export function setup() {
  const token = girisYap()
  const res = http.get(`${BASE_URL}/api/channels`, { headers: { Authorization: `Bearer ${token}` } })
  const kanallar = res.json().map((c) => ({ path: c.mediamtxPath, id: c.id }))
  return { kanallar }
}

function sonSegmentAdi(manifestBody) {
  const satirlar = manifestBody.split('\n').map((s) => s.trim()).filter(Boolean)
  for (let i = satirlar.length - 1; i >= 0; i--) {
    if (!satirlar[i].startsWith('#')) return satirlar[i]
  }
  return null
}

export default function (data) {
  // __VU 1'den başlıyor (k6 kuralı) -- kanallara olabildiğince eşit dağıt.
  const kanallar = data.kanallar
  const kanal = kanallar[(__VU - 1) % kanallar.length]

  const token = girisYap()
  if (!token) {
    sleep(1)
    return
  }
  const authHeaders = { headers: { Authorization: `Bearer ${token}` } }

  const wsUrl = `${BASE_URL.replace(/^http/, 'ws')}/ws/altyazi/${kanal.id}`
  const wsRes = ws.connect(wsUrl, {}, (socket) => {
    socket.on('open', () => {})
    socket.on('message', () => {})
    socket.setTimeout(() => socket.close(), TEST_DURATION_SECONDS * 1000)
  })
  check(wsRes, { 'altyazı websocket bağlandı': (r) => r && r.status === 101 })

  const manifestUrl = `${BASE_URL}/hls/${kanal.path}/index.m3u8`
  const basSaat = Date.now()
  while (Date.now() - basSaat < TEST_DURATION_SECONDS * 1000) {
    const manifest = http.get(manifestUrl, authHeaders)
    check(manifest, { 'manifest 200': (r) => r.status === 200 })

    if (manifest.status === 200) {
      const segment = sonSegmentAdi(manifest.body)
      if (segment) {
        const segmentUrl = `${BASE_URL}/hls/${kanal.path}/${segment}`
        const segRes = http.get(segmentUrl, authHeaders)
        check(segRes, { 'segment 200': (r) => r.status === 200 })
      }
    }
    sleep(MANIFEST_POLL_SECONDS)
  }
}
