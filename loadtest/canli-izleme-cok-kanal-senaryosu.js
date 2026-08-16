// 100 eşzamanlı kullanıcı, FARKLI kanallara dağıtılmış senaryo —
// docs/olcekleme-100-kullanici-plani.md §4/§8'in ölçmek istediği durum:
// tek-kanal-çok-izleyici (bkz. canli-izleme-senaryosu.js) Triton/GPU
// yükünü test ETMİYOR, çünkü yük izleyici sayısına değil kanal sayısına
// bağlı. Bu script her VU'yu KANALLAR listesinden birine atayarak gerçek
// "N farklı kanal aynı anda izleniyor" durumunu taklit ediyor.
//
// ÇALIŞTIRMA (k6 kurulu olmalı):
//
//   BASE_URL=http://localhost:3000 \
//   USERNAME=admin1 PASSWORD=12345678 \
//   VUS=100 \
//   k6 run loadtest/canli-izleme-cok-kanal-senaryosu.js
//
// KANALLAR listesi aşağıda gömülü — test anında GERÇEKTEN canlı olan
// path/id çiftleri olmalı (curl localhost:9997/v3/paths/list ile
// doğrulanıp docker exec postgres ... channels tablosundan id çekildi,
// 16 Ağustos 2026). Yayın değişirse bu liste GÜNCELLENMELİ, aksi halde
// 404'ler "kanal yok" değil "liste eski" anlamına gelir.

import http from 'k6/http'
import ws from 'k6/ws'
import { check, sleep } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:3000'
const USERNAME = __ENV.USERNAME || 'admin1'
const PASSWORD = __ENV.PASSWORD || '12345678'

const KANALLAR = [
  { path: 'trt-haber', id: 'f7209843-c9d9-47db-89d2-b299013bcbba' },
  { path: 'trt-1', id: '5e3beb30-51db-4e28-a4e0-e3835fde319a' },
  { path: 'trt-spor', id: 'f61aeb76-a0cb-4cc4-bde0-72bc2df9822d' },
  { path: 'trt-belgesel', id: '843d749b-24d8-467d-a2cb-865b96dc4874' },
  { path: 'trt-world', id: 'cdb0c852-fa66-4fc1-9769-6a607da9f36a' },
  { path: 'trt-spor-yildiz', id: '87ad4894-1ae5-435a-a3ad-8d860556ca8a' },
  { path: 'trt-cocuk', id: 'bf12227f-3f52-4f04-8f51-0ab229be7470' },
  { path: 'trt-muzik', id: '897da8e6-74d3-459b-9639-c867fcea6e0f' },
  { path: 'trt-avaz', id: '52c40f6f-9b8d-4933-a581-92feff6ea342' },
  { path: 'trt-kurdi', id: '446d54d3-a2c3-4f1b-845d-660433811b3b' },
  { path: 'trt-arabi', id: 'aad16122-f0cd-440e-b7e7-b133c1cbf61d' },
  { path: 'trt-turk', id: '91447ae1-3819-44d7-9da7-98ec1e6fb473' },
  { path: 'dw-en', id: '33f7d5e1-5a43-437c-b79b-c4134e5862fd' },
  { path: 'dw-ar', id: 'a0173c7d-01ea-47b3-ad80-d27bd9134d4f' },
  { path: 'dw-es', id: '79372740-2095-45d2-a8f2-189d7ca3e23b' },
]

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

function sonSegmentAdi(manifestBody) {
  const satirlar = manifestBody.split('\n').map((s) => s.trim()).filter(Boolean)
  for (let i = satirlar.length - 1; i >= 0; i--) {
    if (!satirlar[i].startsWith('#')) return satirlar[i]
  }
  return null
}

export default function () {
  // __VU 1'den başlıyor (k6 kuralı) -- kanallara olabildiğince eşit dağıt.
  const kanal = KANALLAR[(__VU - 1) % KANALLAR.length]

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
