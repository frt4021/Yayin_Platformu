# Ölçekleme Planı — yüzlerce kanal, yüzlerce izleyici

> Durum: **taslak, uygulanmadı.** Bu belge ne yapılacağını ve neden öyle
> yapılacağını anlatıyor; kod yazılmadan önce üzerinde anlaşılması için.

## İçindekiler

1. [Hedef](#1-hedef)
2. [Ölçülen sayılar](#2-ölçülen-sayılar)
3. [Kodda bulunan tavanlar](#3-kodda-bulunan-tavanlar)
4. [Hedef mimari](#4-hedef-mimari)
5. [Faz 1 — Kanal→düğüm ataması](#5-faz-1--kanaldüğüm-ataması)
6. [Faz 2 — Dağıtım katmanı](#6-faz-2--dağıtım-katmanı)
7. [Faz 3 — MediaMTX çoğullama](#7-faz-3--mediamtx-çoğullama)
8. [Faz 4 — STT kuyruğu](#8-faz-4--stt-kuyruğu)
9. [Kapsam dışı — ve neden](#9-kapsam-dışı--ve-neden)
10. [Doğrulanmamış varsayımlar](#10-doğrulanmamış-varsayımlar)
11. [Riskler](#11-riskler)

---

## 1. Hedef

**Yüzlerce eş zamanlı kanal, yüzlerce eş zamanlı izleyici.** Asenkron
altyapı, gerçekten gerektiği yerlerde.

Bu belgede "300 kanal / 500 izleyici" varsayımıyla hesap yapılıyor. Sayılar
değişirse sonuçlar orantılı değişir; mimari kararlar değişmez.

### Bugünkü kapasite

| | Bugün | Hedef |
|---|---|---|
| Eş zamanlı kanal | 24 (`CHANNELS_MAX_ACTIVE`) | ~300 |
| VAD/altyazı kanalı | 20 (`VAD_MAX_CHANNELS`) | ~300 |
| İngest düğümü | 1 | N |
| Dağıtım düğümü | 0 (izleyici doğrudan MediaMTX'e) | N |

---

## 2. Ölçülen sayılar

Hepsi bu proje üzerinde ölçüldü; tahmin değil.

### Kanal başına maliyet

| İş | Kanal başına | 300 kanalda |
|---|---|---|
| Rendition üretimi (VAAPI) | %14 CPU | **42 çekirdek** |
| Rendition üretimi (yazılım, libx264) | %142 CPU | 426 çekirdek |
| DVR kaydı (`-c copy`, transkod yok) | ~%2-3 CPU | ~9 çekirdek |
| VAD ses çıkarma (ffmpeg) | %0,8 CPU · 49 MB | 2,4 çekirdek · 15 GB |
| Silero VAD çıkarımı | 199× gerçek zaman (tek çekirdek) | ~1,5 çekirdek |
| STT (`small`, CPU) | 3,86× gerçek zaman | **GPU kümesi** |

### Depolama

```
300 kanal × 7 gün × 3 Mbps = 68 TB
```

DVR MinIO'ya taşındı; bu alan MinIO'nun deposunda gerekiyor.

### Bant genişliği

```
500 izleyici × 3 Mbps = 1,5 Gbps
2 sn'lik segment      → ~250 istek/sn
```

Mozaik görünümde `capLevelToPlayerSize` küçük rendition seçtiği için karo
başına maliyet daha düşük; üst sınır hesabı tek karo tam kalite üzerinden
yapıldı.

### Çıkarılan sonuç

Rendition üretimi kapatılsa bile 300 kanalın ingest + kayıt + altyazısı tek
makineye sığmıyor. **Çok düğümlü olmak zorunlu.**

---

## 3. Kodda bulunan tavanlar

### 3.1 İş dağıtımı yok — asıl engel

```java
// DvrRecorder.java:96
for (Channel channel : Channel.listActive()) { ... }

// VadService.java:134
for (Channel channel : Channel.listActive()) { ... }
```

Her işçi süreci **bütün** kanalları alıyor. İkinci bir işçi düğümü eklemek
işi bölmüyor, **ikiye katlıyor**: aynı kanal iki kez kaydedilir, aynı ses
iki kez çözümlenir, segmentler çakışır.

Bugün bu sorun görünmüyor çünkü `DVR_RECORDER_ENABLED` ve `VAD_ENABLED`
yalnızca tek bir konteynerde açık. O bayrak, dağıtım probleminin üstünü
örten geçici bir çözüm.

### 3.2 Tek MediaMTX varsayımı

```
docker-compose.yaml:184  MEDIAMTX_API_URL: http://mediamtx:9997
docker-compose.yaml:307  MEDIAMTX_API_URL: http://mediamtx:9997
application.properties:419-420
```

`MediaMtxService` tek bir adrese yazıyor. Kanal ekleme, `ChannelRestorer`,
path durumu yoklaması — hepsi o tek örneği varsayıyor.

### 3.3 Sayısal sınırlar

```
CHANNELS_MAX_ACTIVE=24
VAD_MAX_CHANNELS=20
```

Bunlar yalnızca sayı; arkasındaki gerçek sınır donanım. Ama düğüm başına
sınır olarak yeniden yorumlanmaları gerekiyor (bkz. Faz 1).

### 3.4 İzleyici yükü ingest'e biniyor

İzleyici her segmenti MediaMTX'ten çekiyor. `nginx.conf`'taki `/hls/` bloğu
**her yanıta** `Cache-Control: no-cache, no-store` ekliyor — bu, segmentler
için yanlış ve önüne konacak her önbellek katmanını devre dışı bırakıyor.

---

## 4. Hedef mimari

```
                          ┌─ ingest düğümü 1   MediaMTX + worker    kanal   1- 50
   kontrol düzlemi ───────┼─ ingest düğümü 2                        kanal  51-100
   backend + Postgres     ├─ ingest düğümü 3                        kanal 101-150
   (tek, blocking)        └─ ingest düğümü N
          │
          │                ┌─ dağıtım düğümü 1   nginx, disk + önbellek
          └────────────────┼─ dağıtım düğümü 2                        ──► izleyiciler
                           └─ dağıtım düğümü N
```

### Rol ayrımı

| Katman | Sorumluluk | Ölçekleme |
|---|---|---|
| Kontrol düzlemi | Kanal/kullanıcı yönetimi, atama, API | Dikey — yük düşük |
| İngest düğümü | MediaMTX + DVR kaydı + VAD | **Yatay** — kanal sayısıyla |
| Dağıtım düğümü | Segment ve playlist sunumu | **Yatay** — izleyiciyle |
| STT havuzu | Konuşma tanıma + çeviri | **Yatay** — GPU sayısıyla |

### Neden bu ayrım

İngest yükü **kanal** sayısıyla, dağıtım yükü **izleyici** sayısıyla
büyüyor. İkisi aynı düğümde olursa biri diğerini boğuyor: 500 izleyicinin
segment isteği, kayıt alan ffmpeg'lerin diskini ve ağını meşgul eder ve
**canlı yayın etkilenir**. Ayırmak, izleyici artışının ingest'e hiç
dokunmamasını sağlıyor.

---

## 5. Faz 1 — Kanal→düğüm ataması

**Bu faz olmadan diğerlerinin anlamı yok.** Bugün ikinci bir işçi düğümü
eklemek işi bölmüyor, ikiye katlıyor.

### 5.1 Desen zaten kodda var

Klip ve video kuyrukları tam olarak istenen şeyi yapıyor:

```
Redis BLMOVE  +  FOR UPDATE SKIP LOCKED
```

`ClipQueue`, `VideoQueue`, `ClipWorker`, `VideoWorker` yatay ölçekleniyor;
on işçi açsan onu da farklı iş alır. Yapılacak iş **yeni bir desen icat
etmek değil**, var olanı kanallara taşımak.

Fark şu: klip **bir kerelik iş**, kanal **sürekli sahiplik**. Bu yüzden
kuyruk değil **kira (lease)** gerekiyor.

### 5.2 Şema — `V22__kanal_atamalari.sql`

```sql
create table channel_assignments
(
    channel_id  uuid        primary key references channels (id) on delete cascade,

    -- Düğümün kendine verdiği ad (ortam değişkeni NODE_ID).
    -- Konteyner kimliği DEĞİL: konteyner yeniden yaratıldığında kimlik
    -- değişir ve düğüm tüm kanallarını kaybederdi.
    node_id     varchar(64) not null,

    -- Kiranın bittiği an. Düğüm periyodik olarak uzatıyor; uzatmazsa
    -- (öldü, ağı koptu) süre dolar ve kanal serbest kalır.
    lease_until timestamptz not null,

    claimed_at  timestamptz not null default now()
);

-- "Süresi dolmuş kiralar" sorgusu her tik çalışıyor.
create index idx_channel_assignments_lease on channel_assignments (lease_until);

-- "Bana ait kanallar" sorgusu her tik çalışıyor.
create index idx_channel_assignments_node on channel_assignments (node_id);
```

**Neden ayrı tablo, `channels`'a sütun değil:** atama bilgisi kanalın
kendisine ait değil, o anki yerleşime ait. Kanal tanımı kalıcı, atama
geçici ve sık yazılıyor; aynı satırda tutmak kanal tablosunu saniyede
onlarca kez güncellemek olurdu.

### 5.3 Kiralama mantığı

```java
// ChannelAssignmentService — yeni sınıf

@Scheduled(every = "{cluster.lease-interval}")   // önerilen: 10s
void tick() {
    yenile();   // benim kanallarımın kirasını uzat
    devral();   // süresi dolmuş ya da sahipsiz kanallardan kapasitem kadarını al
}
```

**Yenileme** — sahipliği koruyor:

```sql
update channel_assignments
   set lease_until = now() + :ttl
 where node_id = :me
```

**Devralma** — boşta kalanı almak:

```sql
insert into channel_assignments (channel_id, node_id, lease_until)
select c.id, :me, now() + :ttl
  from channels c
  left join channel_assignments a on a.channel_id = c.id
 where c.active
   and (a.channel_id is null or a.lease_until < now())
 order by c.created_at
 limit :bosKapasite
   for update of c skip locked
on conflict (channel_id) do update
   set node_id = :me, lease_until = excluded.lease_until, claimed_at = now()
 where channel_assignments.lease_until < now();
```

`SKIP LOCKED` burada kritik: iki düğüm aynı anda devralmaya kalkarsa
birbirini beklemiyor, ikisi de farklı kanal alıyor.

**Kira süresi ve tik aralığı:**

| Ayar | Öneri | Gerekçe |
|---|---|---|
| `cluster.lease-interval` | 10 sn | Yenileme sıklığı |
| `cluster.lease-ttl` | 45 sn | Tik aralığının ~4 katı |

TTL'i tik aralığına yakın tutmak, tek bir gecikmiş tikte kanalın el
değiştirmesine yol açar — kayıtta boşluk demek. 4 kat, üç ardışık tik
kaçırmaya tolerans veriyor.

### 5.4 Kod değişiklikleri

| Dosya | Değişiklik |
|---|---|
| `DvrRecorder.java:96` | `Channel.listActive()` → `assignments.myChannels()` |
| `VadService.java:134` | aynı |
| `ChannelService.java:175` | `restoreActiveChannels()` yalnızca kendi kanallarını yazar |
| yeni `ChannelAssignmentService` | kira, devralma, kapasite |
| yeni `cluster.node-id` | `NODE_ID` ortam değişkeni |
| `cluster.max-channels-per-node` | `CHANNELS_MAX_ACTIVE`'in düğüm başına hâli |

### 5.5 Devralma senaryosu

```
t=0    düğüm-2 ölüyor (kanal 51-100 onda)
t=10   düğüm-1 tik: kendi kiralarını uzatıyor, düğüm-2'ninkiler henüz geçerli
t=45   düğüm-2'nin kiraları doluyor
t=50   düğüm-1 ve düğüm-3 tik: boşta 50 kanal görüyor,
       kapasiteleri kadarını SKIP LOCKED ile bölüşüyor
t=50+  kayıt ve altyazı yeni düğümlerde başlıyor
```

**Kabul edilen kayıp:** ölüm ile devralma arasında **~45-55 saniyelik** DVR
boşluğu oluşuyor. Bunu sıfırlamak aktif-aktif çift kayıt gerektirir — iki
kat maliyet ve segment tekilleştirme problemi. Bu ölçekte kabul edilebilir;
zaman çizelgesi boşluğu zaten gösterebiliyor.

### 5.6 Doğrulama

- İki işçi düğümü başlat, kanalların bölündüğünü ve **hiçbirinin
  çakışmadığını** doğrula (`dvr_segments`'te aynı ana ait iki satır olmamalı)
- Bir düğümü öldür, kanalların TTL sonrası devralındığını gör
- Üçüncü düğüm ekle, yükün yeniden dengelendiğini gör

---

## 6. Faz 2 — Dağıtım katmanı

İzleyici yükünü ingest'ten ayırıyor.

### 6.1 Önce bugünkü hata

`frontend/yayin-frontend/nginx.conf`, `/hls/` bloğu:

```nginx
add_header Cache-Control "no-cache, no-store" always;
```

Playlist için doğru, **segmentler için felaket**. Segment içeriği değişmez:
bir kez üretilir, adı sabit, baytları sabit. `no-store` demek "önüne ne
koyarsan koy hiçbiri saklamasın".

Bu satır dururken hiçbir önbellek katmanı iş görmez.

```nginx
location ~ \.m3u8$ {
    add_header Cache-Control "public, max-age=1" always;
}
location ~ \.mp4$ {
    add_header Cache-Control "public, max-age=31536000, immutable" always;
}
```

### 6.2 Segmentler diskten

`mediamtx.yml:27`'de `hlsDirectory: /hls` tanımlı ve **ölçüldü**: MediaMTX
segmentleri gerçekten diske yazıyor (208 `.mp4`, kanal başına ~16). Diskte
`.m3u8` **yok** — playlist bellekte üretiliyor.

Bu ayrım planı belirliyor:

| İstek | Boyut | Nereden |
|---|---|---|
| `*.mp4` segment | 1-3 MB | **doğrudan diskten** — MediaMTX hiç görmez |
| `*.m3u8` | ~1 KB | MediaMTX'ten, önbellekli |

Baytların %99'undan fazlası segmentlerde; onları MediaMTX'ten çıkarmak
yükün neredeyse tamamını kaldırıyor.

### 6.3 Playlist önbelleği

```nginx
proxy_cache_path /var/cache/nginx/hls levels=1:2 keys_zone=hls:16m
                 max_size=512m inactive=60s;

location ~ \.m3u8$ {
    proxy_pass http://mediamtx_upstream;
    proxy_cache hls;
    proxy_cache_valid 200 1s;

    # 1000 istemci aynı anda isterse yukarı akışa TEK istek gider,
    # kalanı bekler. Bu direktif olmadan önbellek soğukken hepsi
    # birden MediaMTX'e vurur ("cache stampede").
    proxy_cache_lock on;
    proxy_cache_lock_timeout 3s;

    # MediaMTX playlist'e no-cache koyuyor olabilir; kendi kuralımız geçerli.
    proxy_ignore_headers Cache-Control Expires;

    proxy_cache_use_stale updating error timeout;
}
```

### 6.4 Çok düğümlü dağıtım

Segmentler diskte olduğu için dağıtım düğümlerinin o diski görmesi gerekiyor.
Üç seçenek, karar Faz 2 başlarken verilecek:

| Yol | Artı | Eksi |
|---|---|---|
| Paylaşımlı dosya sistemi (NFS) | Basit | Tek hata noktası, gecikme |
| Dağıtım düğümü ingest ile aynı makinede | Ağ trafiği yok | İki yük aynı makinede — ayırmanın amacına aykırı |
| İki katmanlı: dağıtım → ingest'ten HTTP ile çeker, önbellekler | Gerçek ayrım | Bir tur daha ağ |

Üçüncüsü öneriliyor: dağıtım düğümü segmenti ingest düğümünden bir kez
çeker, `immutable` olduğu için sonsuza kadar saklar.

### 6.5 Doğrulanmamış — Faz 2 başında ölçülecek

1. **Playlist segmentlere hangi adresle işaret ediyor?** Diskteki ad
   `0b37e64b8b75_audio2_seg258.mp4` biçiminde. Playlist bunu aynen mi
   yazıyor? Yanlış eşleme = izleyici 404 alır.
2. **`cookieCheck` 302'si segmentleri de kapsıyor mu?** Playlist isteğinde
   `Set-Cookie: cookieCheck=1` ve 302 ölçüldü. Segmentte de varsa disk
   sunumu o mekanizmayı atlar.
3. **`hlsDirectory` sunulan dosyaları mı yazıyor, yoksa yalnızca döküm mü?**
   İkincisiyse adlar playlist'le eşleşmeyebilir.

Bu üçü, akan bir yayın üzerinde tek bir `curl` turuyla cevaplanır.

---

## 7. Faz 3 — MediaMTX çoğullama

Faz 1'de kanallar düğümlere dağıtıldı ama `MediaMtxService` hâlâ tek adrese
yazıyor.

### Değişiklik

```java
// Bugün
@ConfigProperty(name = "mediamtx.api-url") String apiUrl;

// Sonra
String apiUrlFor(UUID channelId)   // atamadan düğümü bul, onun adresini ver
```

| Dosya | Değişiklik |
|---|---|
| `MediaMtxService` | Adres kanaldan türetiliyor |
| `ChannelDto.hlsUrl` | Dağıtım düğümünü gösteriyor, ingest'i değil |
| `MediaMtxService.pathStates()` | Tüm düğümlerden toplanıp birleştiriliyor |
| `ChannelRestorer` | Yalnızca kendi düğümünün kanallarını yazıyor |

### Düğüm kaydı

Düğümler kendilerini bir tabloya yazıyor (`cluster_nodes`: node_id, api_url,
hls_url, son_gorulme). Yapılandırmaya sabit adres listesi yazmak, düğüm
eklemeyi yeniden dağıtım gerektiren bir işe çevirirdi.

---

## 8. Faz 4 — STT kuyruğu

### Bugünkü durum

`VadService` kanal başına iş parçacığı açıyor, bölütleri **sınırlı bir
kuyruğa** (64) koyuyor ve iki gönderici iş parçacığı `stt-worker`'a POST
ediyor. Kuyruk dolarsa bölüt düşürülüp `WARN` yazılıyor.

Bu, tek düğüm ve 20 kanal için doğru tasarlanmış. 300 kanalda çalışmaz:
kuyruk süreç içinde, GPU tek ve iş dağıtımı yok.

### Hedef

```
ingest düğümleri ──bölüt──► Redis kuyruğu ──► STT işçi havuzu (GPU)
                                                     │
                                              Redis pub/sub ──► backend ──► WS
```

`ClipQueue` deseninin aynısı: `BLMOVE`, en-az-bir-kez teslim, süpürücü.

### Kapasite

Ölçülen: `small` model CPU'da 3,86× gerçek zaman, iki eş zamanlı işte
1,81-2,3×. GPU'da bu oran çok daha iyi ama **ölçülmedi** — Faz 4'ün ilk işi
tek GPU'nun kaç kanal taşıdığını ölçmek.

300 kanalın hepsinde sürekli konuşma yok; TRT Haber'de ölçülen konuşma oranı
%97 ama müzik kanalında bu çok düşük. Gerçek yük, kanal karışımına bağlı.

### Geri baskı

Kuyruk dolduğunda bugünkü davranış (düşür + `WARN`) korunmalı. Alternatif —
beklemek — VAD'ı durdurur ve **canlı yayını etkiler**; altyazı kaybetmek,
yayını bozmaktan iyidir.

`SubtitleLagMetrics` kapsama ölçümü zaten var; kuyruk düşmeleriyle birlikte
okunduğunda "kaç kanal daha eklenebilir" sorusunun cevabını veriyor.

---

## 9. Kapsam dışı — ve neden

### Backend'i reaktife çevirmek

**Yapılmayacak.** Gerekçe:

Backend bir **kontrol düzlemi**; video ondan geçmiyor. 500 izleyici kanal
listesini 30 saniyede bir çekiyor — **~17 istek/sn**. Blocking Quarkus bunu
rahatlıkla kaldırıyor.

Reaktife geçmek Panache'den Hibernate Reactive'e taşınmak, tüm servisleri
`Uni`/`Multi` ile yeniden yazmak ve her transaction sınırını gözden geçirmek
demek. Büyük risk, ölçülebilir kazanç yok.

**Asenkronun karşılığını verdiği yer istek işleme değil, iş dağıtımı** —
Faz 1 ve Faz 4 tam olarak orası.

### Üçüncü taraf CDN

İzleyiciler kurum içi. CDN'in çözdüğü iki problem (mesafe, bant genişliği
maliyeti) sende yok; kurum içi trafiği internete çıkarıp geri getirmek
gerçek bir gecikme ve dış bağımlılık eklerdi.

Faz 2'deki cache başlıkları doğru yazıldığında, ileride CDN gerekirse
önüne takmak yapılandırma işi olur.

### Aktif-aktif kayıt

Düğüm ölümünde ~45 sn boşluk kabul ediliyor (5.5). Sıfırlamak iki kat
maliyet ve segment tekilleştirme problemi getirir.

---

## 10. Doğrulanmamış varsayımlar

Bunlar plana temel oluşturuyor ama **ölçülmedi**; ilgili faz başlarken
doğrulanmalı:

| # | Varsayım | Nerede kritik |
|---|---|---|
| 1 | Playlist, diskteki segment adlarını aynen yazıyor | Faz 2 — yanlışsa disk sunumu çalışmaz |
| 2 | `cookieCheck` yalnızca playlist'te | Faz 2 |
| 3 | `hlsDirectory` sunulan dosyaları yazıyor | Faz 2 — yanlışsa Faz 2 baştan tasarlanmalı |
| 4 | Tek MediaMTX 50 kanal taşıyor | Faz 1 — düğüm başına kapasite bundan geliyor |
| 5 | GPU'da STT kanal başına maliyeti | Faz 4 — havuz boyutu bundan geliyor |
| 6 | 3 Mbps ortalama kanal bit hızı | Depolama ve bant genişliği hesabı |

**4. madde özellikle önemli:** düğüm başına kanal sayısı bilinmeden kaç
düğüm gerektiği de bilinemez. Faz 1'in ilk işi tek bir düğümü doyurup
sınırı ölçmek olmalı.

---

## 11. Riskler

| Risk | Etki | Azaltma |
|---|---|---|
| İki düğüm aynı kanalı kaydeder | Segmentler çakışır, maliyet iki katı | `channel_id` birincil anahtar — veritabanı zorluyor |
| Kira süresi yanlış ayarlanır | Kanallar sürekli el değiştirir, kayıt delik deşik | TTL ≥ 4 × tik; ölçülerek doğrulanmalı |
| Postgres atama yükü altında darboğaz | Tüm küme etkilenir | Tik aralığı 10 sn; 300 kanalda 30 satır/sn — sorun beklenmiyor |
| Dağıtım düğümü diski göremez | İzleyici 404 alır | Faz 2 varsayım 1-3 önce ölçülmeli |
| Faz 1 yarım kalır, ikinci düğüm eklenir | **Sessiz çift kayıt** | Faz 1 bitmeden ikinci düğüm açılmamalı |

Son satır en tehlikelisi: bugün `DVR_RECORDER_ENABLED` bayrağı çift kaydı
engelliyor ama bu bir düğüm dağıtım mekanizması değil, tek düğümlü olmanın
sonucu. Bayrağa güvenip ikinci işçi açmak sessizce iki kat maliyet üretir.

---

## Sıra ve tahmini büyüklük

| Faz | İçerik | Bağımlılık |
|---|---|---|
| **1** | Kanal→düğüm ataması | — |
| **2** | Dağıtım katmanı | Bağımsız, paralel yürüyebilir |
| **3** | MediaMTX çoğullama | Faz 1 |
| **4** | STT kuyruğu | Faz 1 |

Faz 1 ve Faz 2 birbirinden bağımsız. Faz 2'nin ilk adımı (cache
başlıklarının düzeltilmesi) **bugün yapılabilir** ve tek başına bile
MediaMTX üzerindeki izleyici yükünü belirgin düşürür.
