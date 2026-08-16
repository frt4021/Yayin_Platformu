// 100 eşzamanlı izleyici senaryosu — docs/olcekleme-100-kullanici-plani.md
// §5/§10.3'teki "hiç yapılmadı" yük testi.
//
// Gerçek istemcinin yaptığı üç şeyi taklit ediyor:
//   1. Giriş: POST /api/auth/login (backend'in kendi Keycloak vekaleti)
//   2. HLS: manifesti periyodik GET, en yeni segmenti GET
//   3. Canlı altyazı: /ws/altyazi/{channelId} WebSocket'i açık tutup dinler
//
// ÇALIŞTIRMA (k6 kurulu olmalı, https://k6.io/docs/get-started/installation/):
//
//   BASE_URL=http://localhost:3000 \
//   CHANNEL_PATH=kanal1 \
//   CHANNEL_ID=<channels tablosundaki uuid> \
//   USERNAME=admin1 PASSWORD=12345678 \
//   k6 run --vus 100 --duration 5m loadtest/canli-izleme-senaryosu.js
//
// Test sırasında Grafana'daki "Backend & Video-Worker Genel", "MediaMTX",
// "Servis Durumu" dashboard'ları (localhost:3001) izlenerek CPU/bellek/
// bağlantı davranışı gözlemlenmeli — bu script'in kendisi Prometheus'a
// veri yazmıyor, yalnızca k6'nın kendi özet çıktısını üretir.
//
// NOT: CHANNEL_PATH/CHANNEL_ID gerçek, o an yayında olan bir kanala ait
// olmalı — aksi halde HLS/WS istekleri 404/boş döner ve test anlamsız
// (yayında olmayan bir kanalı "yük" olarak ölçmüş olursunuz).

import http from 'k6/http'
import ws from 'k6/ws'
import { check, sleep } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000'
const CHANNEL_PATH = __ENV.CHANNEL_PATH || 'kanal1'
const CHANNEL_ID = __ENV.CHANNEL_ID || ''
const USERNAME = __ENV.USERNAME || 'admin1'
const PASSWORD = __ENV.PASSWORD || '12345678'

// hls.js'in gerçek polling aralığına yakın — MediaMTX'in varsayılan segment
// süresi ~2-6sn (kanaldan kanala değişiyor, bkz. CLAUDE.md TARGETDURATION
// notu); manifesti her segment kadar sık yoklamak gerçekçi.
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

// Playlist'teki son segment adını çıkarır — "#" ile başlamayan, boş
// olmayan son satır (HLS manifest formatı: yorum/etiket satırları # ile
// başlar, segment dosya adları düz satır).
function sonSegmentAdi(manifestBody) {
  const satirlar = manifestBody.split('\n').map((s) => s.trim()).filter(Boolean)
  for (let i = satirlar.length - 1; i >= 0; i--) {
    if (!satirlar[i].startsWith('#')) return satirlar[i]
  }
  return null
}

export default function () {
  const token = girisYap()
  if (!token) {
    sleep(1)
    return
  }
  const authHeaders = { headers: { Authorization: `Bearer ${token}` } }

  const wsUrl = `${BASE_URL.replace(/^http/, 'ws')}/ws/altyazi/${CHANNEL_ID}`
  const wsRes = ws.connect(wsUrl, {}, (socket) => {
    socket.on('open', () => {})
    socket.on('message', () => {})
    socket.setTimeout(() => socket.close(), TEST_DURATION_SECONDS * 1000)
  })
  check(wsRes, { 'altyazı websocket bağlandı': (r) => r && r.status === 101 })

  const manifestUrl = `${BASE_URL}/hls/${CHANNEL_PATH}/index.m3u8`
  const basSaat = Date.now()
  while (Date.now() - basSaat < TEST_DURATION_SECONDS * 1000) {
    const manifest = http.get(manifestUrl, authHeaders)
    check(manifest, { 'manifest 200': (r) => r.status === 200 })

    if (manifest.status === 200) {
      const segment = sonSegmentAdi(manifest.body)
      if (segment) {
        const segmentUrl = `${BASE_URL}/hls/${CHANNEL_PATH}/${segment}`
        const segRes = http.get(segmentUrl, authHeaders)
        check(segRes, { 'segment 200': (r) => r.status === 200 })
      }
    }
    sleep(MANIFEST_POLL_SECONDS)
  }
}
