# Faz 2 Planı — 7 Günlük DVR ve Klip Çıkarma

**Hedef:** Kullanıcı geçmişe gidip istediği aralığı klip olarak indirebilsin.

Bu plan, MediaMTX'in playback sunucusu üzerinde canlı olarak yapılan
doğrulamalara dayanıyor. Ölçülen değerler ve doğrulanan davranışlar
açıkça işaretlendi.

---

## 1. Depolama — karar: 7 gün diskte tutulacak

### Bit hızı ne demek

Kanalın **Mbps** değeri, yayının saniyede taşıdığı veri miktarıdır. Kayıt
sırasında yeniden kodlama yapılmadığı için (stream copy) bu, **saniyede
diske yazılan miktarla aynıdır**.

Bit ile byte karıştırılmamalı: 8 bit = 1 byte, yani **Mbps ÷ 8 = saniyede MB**.

```
2 Mbps = 2.000.000 bit/sn = 250.000 byte/sn = 0,25 MB/sn
```

Ölçümle doğrulaması:

| | |
|---|---|
| Video 2.000 kbps + ses 64 kbps | 2.064 kbps = 258 KB/sn |
| Hesaplanan dakikalık | 15,5 MB |
| **Ölçülen dakikalık** | **15,7 MB** |

Aradaki fark fMP4 kapsayıcı yükü (~%1,5).

### Disk boyutlandırma

7 günlük DVR için gereken alan:

| Bit hızı | Kanal başına | 4 kanal | 8 kanal | 16 kanal |
|---|---|---|---|---|
| 1 Mbps | 76 GB | 0.30 TB | 0.60 TB | 1.21 TB |
| 2 Mbps | 151 GB | 0.60 TB | 1.21 TB | **2.42 TB** |
| 4 Mbps | 302 GB | 1.21 TB | 2.42 TB | **4.84 TB** |
| 6 Mbps | 454 GB | 1.81 TB | 3.63 TB | **7.26 TB** |
| 8 Mbps | 605 GB | 2.42 TB | 4.84 TB | 9.68 TB |
| 10 Mbps | 756 GB | 3.02 TB | 6.05 TB | 12.10 TB |

Değerler **video + ses toplamı** üzerinden okunmalı.

%20 boş alan payıyla alınması gereken disk (16 kanal, 7 gün):

| Bit hızı | Disk |
|---|---|
| 2 Mbps | **2.9 TB** |
| 4 Mbps | **5.8 TB** |
| 6 Mbps | **8.7 TB** |

Makinede şu an 448 GB boş — bu alanla 16 kanalda yalnızca **30,5 saat**
saklanır. 7 gün hedefi için ayrı disk şart.

### Uygulaması

Disk `/recordings` olarak bağlanır; tek satırlık compose değişikliği,
mimari değişmez:

```yaml
volumes:
  - /mnt/dvr:/recordings          # ./mediamtx-data/recordings yerine
```

`recordDeleteAfter: 168h` (7 gün) ile MediaMTX süresi dolan dosyaları
kendisi siler.

> **Açık kalan tek sayı:** gerçek kanal bit hızlarınız. Yukarıdaki tabloda
> hangi satırda olduğunuzu bilmeden disk boyutu seçilemez.

### Disk dolarsa ne olur

Bu, fazın en ciddi işletme riski. MediaMTX disk dolduğunda kayıt yazamaz;
kötü senaryoda **canlı yayın da etkilenir**. Bu yüzden:

- `recordDeleteAfter` her zaman disk kapasitesiyle tutarlı olmalı
- Disk kullanımı izlenmeli ve %85'te uyarı verilmeli (MediaMTX `metrics`
  ucu bunun için açılabilir)
- Yeni kanal DVR'a açılmadan önce alan hesabı yapılmalı — uygulama
  tarafında bir kontrol eklenebilir (bkz. §9)

---

## 2. Doğrulanan: MediaMTX playback sunucusu işi görüyor

Faz 2'nin çekirdeği MediaMTX'te hazır. `playback: true` yapıldığında
`:9996` üzerinde iki uç açılıyor:

### Kayıt aralıklarını listeleme

```
GET :9996/list?path=kanal01
```

Gerçek yanıt:

```json
[
  {"start":"2026-07-31T07:56:50.978283Z","duration":2398.127,
   "url":"http://.../get?duration=2398.127&path=kanal01&start=..."},
  {"start":"2026-07-31T10:34:38.125248Z","duration":1875.110,
   "url":"..."}
]
```

Bitişik segmentleri tek aralık olarak birleştiriyor; **kayıt boşlukları
ayrı aralık olarak görünüyor**. Zaman çizelgesindeki "bu saatte kayıt var
mı" bilgisi doğrudan buradan gelir — ayrıca bir indeks tutmaya gerek yok.

### Klip çekme

```
GET :9996/get?path=kanal01&start=<ISO8601>&duration=<saniye>&format=mp4
```

Doğrulandı: 10 saniyelik istek → **10.007 sn**, 2.5 MB, geçerli
`h264 1280x720 + aac` mp4. Doğrudan indirilip oynatılabiliyor.

Kesim MediaMTX tarafında yapılıyor; ffmpeg ile yeniden kodlamaya gerek yok.
Bu, planın en pahalı parçasını ortadan kaldırıyor.

### Sonuç

Kendi segment indeksimizi, birleştirme mantığımızı ve ffmpeg kesme
katmanımızı **yazmıyoruz**. Backend'in işi yetkilendirme ve sunum.

---

## 3. Mimari

```
                    MediaMTX
  Kaynak ──► kayıt ──► /recordings (disk)
                          │
                          ├── :9996/list   ─┐
                          └── :9996/get    ─┤  yalnızca iç ağ,
                                            │  dışarı KAPALI
                                            ▼
                                    Backend :8081
                                    ├─ yetki kontrolü
                                    ├─ /api/channels/{id}/dvr/timeline
                                    └─ /api/channels/{id}/clips
                                            │
                                            ▼
                                       Tarayıcı
```

**`:9996` dışarı açılmaz.** Backend proxy'ler ve yetkiyi orada uygular.
Böylece canlı yayında yaşadığımız "herkes izleyebiliyor" açığı DVR
tarafında baştan oluşmaz.

---

## 4. Veri modeli

### `channels` tablosuna eklenecek

```sql
alter table channels add column dvr_enabled boolean not null default false;
```

Kayıt kanal bazında açılır. Uygulama bunu MediaMTX'e
`PATCH /v3/config/paths/patch/<path> {"record": true}` ile yansıtır —
kanal ekleme/güncellemedeki mevcut akışın içine girer.

Saklama süresi şimdilik global (`recordDeleteAfter: 168h`). Kanal bazında
farklılaştırma gerekirse sonradan sütun eklenir.

### Yeni tablo: `clips`

```sql
create table clips (
    id           uuid        primary key default gen_random_uuid(),
    channel_id   uuid        not null references channels (id),
    requested_by uuid        not null references users (id),
    start_at     timestamptz not null,
    end_at       timestamptz not null,
    status       varchar(16) not null,   -- BEKLIYOR | ISLENIYOR | HAZIR | HATA
    object_key   varchar(512),           -- MinIO nesne anahtarı
    size_bytes   bigint,
    error        text,
    created_at   timestamptz not null default now(),
    completed_at timestamptz
);
```

Klip kaydı **kanal silinse bile** anlamlı olmalı mı? Şu an foreign key
kanala bağlı; kanal silinince klipler de gitmeli mi yoksa kalmalı mı —
karar verilmesi gereken bir nokta (bkz. §9).

---

## 5. API yüzeyi

| Uç | Yetki | İş |
|---|---|---|
| `GET /api/channels/{id}/dvr/timeline?from=&to=` | giriş yapmış | Kayıt aralıkları — `:9996/list` çıktısının süzülmüş hali |
| `GET /api/channels/{id}/dvr/stream?start=&duration=` | giriş yapmış | Geri sarma oynatımı (proxy) |
| `POST /api/channels/{id}/clips` | Yönetici, Moderatör | `{start, end}` → klip işi oluşturur |
| `GET /api/clips?channelId=` | giriş yapmış | Klip listesi ve durumları |
| `GET /api/clips/{id}/download` | sahibi veya Yönetici | İndirme (imzalı MinIO adresi) |
| `DELETE /api/clips/{id}` | sahibi veya Yönetici | Klip sil |

---

## 6. Klip çıkarma — karar: asenkron, kuyruklu

Senkron indirme yapılmayacak. Her klip isteği bir **iş** olarak kaydedilip
kuyruğa alınacak.

```
Kullanıcı ──POST /api/channels/{id}/clips {start, end}
                │
                ├─ clips tablosuna BEKLIYOR olarak yaz
                ├─ Redis kuyruğuna it
                └─ 202 Accepted + clip id  ◄── istek burada biter
                                │
        ┌───────────────────────┘
        ▼
   Klip işçisi (arka plan)
        ├─ durum: ISLENIYOR
        ├─ MediaMTX :9996/get'ten akışı çek
        ├─ MinIO'ya AKIŞ HALİNDE yaz (belleğe almadan)
        ├─ durum: HAZIR, object_key + size_bytes
        └─ hata olursa: HATA + sebep
                                │
   Kullanıcı ──GET /api/clips/{id}──► durum sorgulama
              ──GET /api/clips/{id}/download──► imzalı MinIO adresi
```

**Neden asenkron:** 2 Mbps'te 1 saatlik klip ≈ 900 MB. Senkron indirmede
HTTP bağlantısı dakikalarca açık kalır; kullanıcı sekmeyi kapatınca iş
boşa gider, sunucu yeniden başlarsa iş kaybolur. Kuyrukta iş kalıcıdır ve
yeniden denenebilir.

**Akış halinde yazma önemli:** işçi klibi belleğe veya geçici diske
almadan MediaMTX'ten okuyup MinIO'ya aktarmalı. Aksi halde eşzamanlı birkaç
uzun klip belleği tüketir.

**İşçi tekliği:** birden fazla backend kopyası çalışırsa aynı iş iki kez
işlenmemeli. Redis kuyruğunda iş çekme atomik olmalı (`BLMOVE` ile işleme
listesine taşıma), ya da `clips` satırında iyimser kilit kullanılmalı.

**Eşzamanlılık sınırı:** aynı anda kaç klip işlenebileceği yapılandırılabilir
olmalı (başlangıç: 2). Sınırsız bırakılırsa disk ve ağ doyar, canlı yayını
etkiler.

**Yeniden deneme:** ağ hatası gibi geçici sorunlarda sınırlı sayıda
(ör. 3) yeniden deneme; sonra `HATA`. "Kayıt bulunamadı" gibi kalıcı
hatalarda deneme yapılmamalı.

**Temizlik:** kliplerin ömrü olmalı; aksi halde MinIO sınırsız büyür.
Başlangıç için 30 gün sonra otomatik silme, süresi `clips` tablosunda
görünür.

---

## 7. Zaman çizelgesi arayüzü

En çok iş burada.

**Veri:** `/api/channels/{id}/dvr/timeline?from=&to=` → aralık listesi.
Kayıt olan/olmayan bölgeler doğrudan çizilir.

**Bileşen:**
- 7 günlük yatay şerit; gün → saat → dakika olarak yakınlaşma
- Kayıt olan aralıklar dolu, boşluklar boş çizilir
- Sürükleyerek aralık seçimi; seçim süresi ve tahmini boyut anlık gösterilir
- Seçimin başına atlayan önizleme oynatıcısı (mevcut `HlsPlayer` yeniden
  kullanılabilir; kaynak `dvr/stream` olur)
- "Klip oluştur" düğmesi

**Önemli ayrıntı:** kullanıcı 7 günlük aralıkta gezinirken sürekli istek
atmamalı. Zaman çizelgesi verisi kaba çözünürlükte bir kez çekilip
istemcide yakınlaştırılmalı.

---

## 8. İş kırılımı

| # | İş | Bağımlılık |
|---|---|---|
| 1 | Disk alımı ve `/recordings` olarak bağlanması | **§1 — her şeyi bloke eder** |
| 2 | `playback: yes`, `recordDeleteAfter: 168h`, `:9996` yalnızca iç ağda | 1 |
| 3 | `channels.dvr_enabled` sütunu + kanal CRUD'a bağlanması | — |
| 4 | Backend: `DvrService` — `/list` proxy'si, yetki, tarih süzme | 2 |
| 5 | Backend: `GET .../dvr/timeline` ve `dvr/stream` | 4 |
| 6 | Frontend: zaman çizelgesi bileşeni + geri sarma oynatımı | 5 |
| 7 | `clips` tablosu (migration) | — |
| 8 | Backend: klip işi oluşturma ucu + Redis kuyruğu | 7 |
| 9 | Backend: klip işçisi — MediaMTX'ten MinIO'ya akış, durum yönetimi | 8 |
| 10 | Backend: durum sorgulama ve imzalı indirme ucu | 9 |
| 11 | Frontend: aralık seçimi + klip oluşturma | 6, 8 |
| 12 | Frontend: klip listesi, durum takibi, indirme | 10 |
| 13 | Süresi dolan kliplerin temizlenmesi (zamanlanmış iş) | 9 |

**Ara çıktı (1–6):** kullanıcı geçmişe gidip izleyebiliyor. Klip yok ama
DVR çalışıyor — gösterilebilir bir aşama.

**Faz çıktısı (1–12):** kullanıcı aralık seçip klip indirebiliyor.

13 işletme borcu; kliplerin sınırsız birikmesini engeller, ilk sürümde
atlanabilir ama unutulmamalı.

---

## 9. Kararlar

### Verilenler

| Konu | Karar |
|---|---|
| Saklama | **7 gün, yerel diskte** (`recordDeleteAfter: 168h`) |
| Depolama stratejisi | **Disk eklenecek** — kapsam daraltma veya düşük kaliteli kopya yok |
| Klip çıkarma | **Asenkron, Redis kuyruklu**; senkron indirme yok |

### Hâlâ açık

1. **Gerçek kanal bit hızlarınız kaç Mbps?** Disk boyutu buna bağlı —
   16 kanal için 2 Mbps'te 2.9 TB, 6 Mbps'te 8.7 TB. Tek en kritik sayı.
2. **Hangi kanallarda DVR açık olacak?** 16'sı da mı? Alan hesabı buna göre.
3. **Klip süre üst sınırı var mı?** Asenkron olduğu için teknik zorunluluk
   yok, ama sınırsız bırakmak "7 günü tek klip yap" isteğine kapı açar
   (≈150 GB). Öneri: 2 saat.
4. **Klipler kime görünür?** Sadece oluşturana mı, tüm yöneticilere mi?
5. **Kanal silinince klipleri ne olacak?** Silinsin mi, kalsın mı?
6. **Klip saklama süresi** — öneri 30 gün. Sonrasında otomatik silinsin mi?

---

## 10. Riskler

| Risk | Etki | Azaltma |
|---|---|---|
| **Disk dolarsa MediaMTX kayıt yazamaz** ve canlı yayın da etkilenebilir | Yüksek | Disk kullanımı izleme + alarm; `recordDeleteAfter` sıkı tutma |
| Playback sunucusu kayıtları **yerel diskte** arar; MinIO'ya taşınamaz | Orta | DVR yerel diskte kalır, MinIO yalnızca üretilmiş klipler için |
| MediaMTX tek nokta arızası — düşerse DVR erişimi de gider | Orta | Canlı yayınla aynı risk; kanal bölüştürme aynı çözümü kapsar |
| Uzun klip isteği belleği/diski zorlar | Düşük | v1'de süre sınırı, v2'de akış halinde MinIO'ya yazma |
| Saat dilimi karışıklığı (kayıtlar UTC) | Düşük | API'de her yerde UTC, gösterimde yerel saat |
