# Klip ve Video Altyazısı — Plan

> Durum: **taslak, uygulanmadı.** Canlı altyazı çalışıyor; bu belge onu
> kliplere, geriye sarmaya ve kütüphane videolarına nasıl genişleteceğimizi
> anlatıyor.

## İçindekiler

1. [Bugün nerede var, nerede yok](#1-bugün-nerede-var-nerede-yok)
2. [Üç durumun zorluğu aynı değil](#2-üç-durumun-zorluğu-aynı-değil)
3. [Aşama A — Geriye sarma ve klipte gösterim](#3-aşama-a--geriye-sarma-ve-klipte-gösterim)
4. [Aşama B — Klip altyazısını kalıcı kılmak](#4-aşama-b--klip-altyazısını-kalıcı-kılmak)
5. [Aşama C — Kütüphane videosu altyazısı](#5-aşama-c--kütüphane-videosu-altyazısı)
6. [Aşama D — WebVTT indirme ve gömme](#6-aşama-d--webvtt-indirme-ve-gömme)
7. [Öncelik: canlı asla aç kalmamalı](#7-öncelik-canlı-asla-aç-kalmamalı)
8. [Kapsam dışı](#8-kapsam-dışı)
9. [Riskler ve açık sorular](#9-riskler-ve-açık-sorular)

---

## 1. Bugün nerede var, nerede yok

| Nerede | Altyazı | Veri var mı |
|---|---|---|
| Canlı karo | **evet** | — |
| Geriye sarma (DVR) | hayır | **evet** |
| Klip | hayır | **evet** |
| Kütüphane videosu | hayır | **hayır** |

`SubtitleOverlay` tek yerde kullanılıyor: `PersistentPlayers.tsx:396`. Orada
bile geri sarılmış bölümde kapatılıyor ve gerekçesi kodda yazılı:

```tsx
{/* Geri sarılan bölümde gösterilmiyor: o düz bir mp4 ve
    playingDate() canlı yayın anını veremez. */}
{subtitleLang !== 'kapali' && !rewindUrl && (
```

`ClipsPage` ve `VideoPlayerDialog`'da altyazıya dair hiçbir iz yok.

---

## 2. Üç durumun zorluğu aynı değil

### Klip ve geriye sarma — veri **zaten üretilmiş**

`altyazilar` tablosu `channel_id + zaman aralığı` ile anahtarlı
(`V19__altyazilar.sql:44`). Klip de `channel_id + start_at/end_at` taşıyor.

Yani **bir klibin altyazısı şu anda veritabanında duruyor** — sadece kimse
sormuyor.

Eksik olan tek şey **eşleştirme**. Canlıda `playingDate()` kullanılıyor, o da
HLS'in `EXT-X-PROGRAM-DATE-TIME` etiketinden geliyor. Düz bir mp4'te o etiket
yok — ama gerekmiyor da:

```
mutlak an = klip.startAt + video.currentTime
```

Klibin başlangıcı biliniyor, oynatıcının konumu biliniyor. Hepsi bu.

### Kütüphane videosu — veri **yok**

Kütüphane videosunun kanalı yok, mutlak zamanı yok. Altyazı **üretilmeli**:
ses çıkar → VAD → STT → çeviri. Boru hattı var ama canlı RTSP'ye bağlı;
dosya üzerinden çalışan bir toplu iş olarak yeniden kurulması gerekiyor.

---

## 3. Aşama A — Geriye sarma ve klipte gösterim

**En küçük iş, en büyük kazanç.** Yeni veri üretilmiyor, var olan
gösteriliyor.

### 3.1 `SubtitleOverlay`'e ikinci zaman kaynağı

Bugün bileşen `capture.playingDate()`'e bağlı. Bunun yerine **bir zaman
sağlayıcı** alacak:

```tsx
type ZamanKaynagi = () => Date

// Canlı: PDT'den
() => capture.current!.playingDate()

// Klip / geri sarma: sabit başlangıç + oynatıcı konumu
() => new Date(baslangic.getTime() + video.currentTime * 1000)
```

Eşleştirme mantığının geri kalanı **hiç değişmiyor** — zaten iki mutlak zaman
karşılaştırıyor. Değişen yalnızca "şu an hangi anı izliyorum" sorusunun
cevabının nereden geldiği.

### 3.2 Klip oynatıcısına bindirme

`ClipsPage` klibi bir `<video>` ile oynatıyor. Eklenecekler:

| | |
|---|---|
| Dil seçici | `SUBTITLE_LANGS`, canlıdakiyle aynı |
| `SubtitleOverlay` | `channelId = clip.channelId`, zaman kaynağı = `startAt + currentTime` |
| Geçmiş çekme | Tek istek: `?from=clip.startAt&to=clip.endAt` |

WebSocket **gerekmiyor**: klip geçmiş bir aralık, yeni altyazı gelmeyecek.
Tek REST çağrısı yeterli ve tamamı önceden yüklenebiliyor.

### 3.3 Geriye sarmada bindirme

`PersistentPlayers.tsx:395`'teki `!rewindUrl` koşulu kalkıyor; geri sarılan
bölümün başlangıcı `LiveRewind` tarafından zaten biliniyor
(`Date.now() - (seconds + 5) * 1000`), o değer zaman kaynağına veriliyor.

### 3.4 Sınır

`clip.channelId` **null olabilir** (V21: kanal silinince bağ kopuyor). O
durumda altyazı gösterilemez — ve zaten yoktur, çünkü:

```sql
channel_id UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE
```

Kanal silinince altyazılar da gidiyor. Bunu Aşama B çözüyor.

### 3.5 Büyüklük

Yalnızca ön yüz. Yeni tablo yok, yeni uç yok, arka uç değişikliği yok.

---

## 4. Aşama B — Klip altyazısını kalıcı kılmak

### Problem

Aşama A'dan sonra klip altyazısı **kanala bağımlı** kalıyor:

- Kanal silinirse altyazı da siliniyor (CASCADE)
- Canlı altyazı saklama süresi dolarsa gidiyor
- Klip kalıcı ama altyazısı geçici — tutarsız

Klip **bağımsız bir çıktı** olmalı; kaynağı silinince içeriği eksilmemeli.

### Çözüm: klip üretilirken altyazıyı **kopyala**

`ClipWorker` klibi MinIO'ya yazarken, o aralığın altyazılarını da klibe ait
olacak şekilde kopyalıyor.

```
V22__klip_altyazilari.sql

create table klip_altyazilari
(
    id         uuid primary key default gen_random_uuid(),
    clip_id    uuid not null references clips (id) on delete cascade,

    -- Klip BAŞINDAN itibaren göreli milisaniye.
    --
    -- Mutlak zaman DEĞİL: klip bağımsız bir dosya ve "yayındaki an" bilgisi
    -- onun için anlamını yitiriyor. Göreli olması ayrıca WebVTT üretimini
    -- doğrudan mümkün kılıyor (Aşama D) -- VTT ipuçları da göreli.
    baslangic_ms integer not null,
    bitis_ms     integer not null,

    kaynak_dil varchar(8),
    metinler   jsonb not null,

    constraint klip_altyazi_araligi check (bitis_ms > baslangic_ms)
);

create index idx_klip_altyazilari on klip_altyazilari (clip_id, baslangic_ms);
```

### Neden göreli zaman

Canlı tarafta mutlak zaman **zorunluydu**: canlı yayının başlangıcı yok, tek
ortak referans duvar saati. Klipte tam tersi — klibin başlangıcı var, sonu
var, ve "yayındaki an" bilgisi kullanıcı için anlamsız.

Göreli olması üç şeyi kolaylaştırıyor:

| | |
|---|---|
| Oynatıcı | `currentTime * 1000` ile doğrudan karşılaştırma; tarih aritmetiği yok |
| WebVTT | İpuçları zaten göreli — dönüşüm doğrudan |
| Gömme | ffmpeg altyazı izini göreli bekliyor |

### Nerede yapılıyor

`ClipWorker.markReady()` içinde, klip HAZIR'a geçmeden hemen önce:

```sql
insert into klip_altyazilari (clip_id, baslangic_ms, bitis_ms, kaynak_dil, metinler)
select :clipId,
       extract(epoch from (a.baslangic - :clipStart)) * 1000,
       extract(epoch from (a.bitis     - :clipStart)) * 1000,
       a.kaynak_dil, a.metinler
  from altyazilar a
 where a.channel_id = :channelId
   and a.baslangic < :clipEnd
   and a.bitis     > :clipStart;
```

Uçtaki bölütler klip sınırlarını taşabiliyor; kırpma **yapılmıyor** — taşan
kısım oynatılmayan bir aralığa denk geldiği için zararsız ve kırpmak cümleyi
ortasından bölerdi.

### Geriye dönük

Mevcut klipler için altyazı yok. İsteğe bağlı bir "altyazıyı yeniden üret"
ucu eklenebilir ama kaynak altyazılar silinmiş olabilir — o durumda Aşama C'nin
hattı klip dosyası üzerinde çalıştırılır.

---

## 5. Aşama C — Kütüphane videosu altyazısı

Burada altyazı **üretiliyor**, kopyalanmıyor.

### 5.1 Neden mevcut hat doğrudan kullanılamıyor

`VadService` canlıya göre tasarlanmış:

| Canlı | Video |
|---|---|
| RTSP'den sürekli akış | MinIO'da sonlu dosya |
| Gerçek zamanlı (1×) | **olabildiğince hızlı** |
| Kanal başına iş parçacığı | iş kuyruğundan |
| Zamanlama duvar saatinden | dosya başından |
| Yetişemezse bölüt düşer | **düşmemeli** — sonuç eksik kalır |

Son satır önemli: canlıda bölüt düşürmek doğru karar (`VadService` kuyruğu
64 ile sınırlı ve dolunca düşürüyor), çünkü canlı beklemez. Videoda bölüt
düşürmek **sessizce eksik altyazı** demek ve kabul edilemez.

### 5.2 Hat

```
video HAZIR ──► altyazı işi kuyruğa ──► altyazi-worker
                                            │
      ffmpeg: MinIO → PCM 16 kHz mono ──────┤
      Silero-VAD → bölütler                 │
      stt-worker /transcribe ───────────────┤
                                            ▼
                                   video_altyazilari
```

Kuyruk `VideoQueue` deseninin aynısı: Redis `BLMOVE` + `FOR UPDATE SKIP
LOCKED` + süpürücü. **Yeni desen yok**, var olanın ikinci örneği.

### 5.3 Şema

```
V23__video_altyazilari.sql

create table video_altyazilari
(
    id           uuid primary key default gen_random_uuid(),
    video_id     uuid not null references videos (id) on delete cascade,
    baslangic_ms integer not null,
    bitis_ms     integer not null,
    kaynak_dil   varchar(8),
    metinler     jsonb not null
);

create index idx_video_altyazilari on video_altyazilari (video_id, baslangic_ms);

-- Videonun altyazı durumu: YOK | KUYRUKTA | ISLENIYOR | HAZIR | HATA
alter table videos add column altyazi_durumu varchar(16) not null default 'YOK';
alter table videos add column altyazi_hatasi text;
```

`klip_altyazilari` ile **aynı şekil** (göreli ms + JSONB) — ön yüzde tek bir
bindirme bileşeni ikisini de okuyabiliyor.

### 5.4 Ses çıkarma

Canlıdaki komutun dosya sürümü:

```
ffmpeg -v error -i <imzalı MinIO adresi> -vn -ac 1 -ar 16000 -f s16le -
```

`-re` **yok**: canlıda gerçek zamanı taklit etmek gerekiyordu, burada tam
tersi — olabildiğince hızlı okunmalı. Bu tek fark, işin gerçek zamandan çok
daha hızlı bitmesini sağlıyor.

Dosya **indirilmiyor**, imzalı adresten akıtılıyor: 2 saatlik bir video
GB'larca eder ve diske almak gereksiz.

### 5.5 Tetikleme

| Ne zaman | Nasıl |
|---|---|
| Video HAZIR olunca | Otomatik, `videos.subtitles-auto` açıksa |
| Elle | `POST /api/videos/{id}/altyazi` |

Varsayılan **kapalı** öneriliyor: her yüklenen videoyu çözümlemek GPU'yu
doldurur ve çoğu video altyazı gerektirmez.

### 5.6 Süre tahmini

Ölçülen: `small` model CPU'da 3,86× gerçek zaman. Yani **1 saatlik video ≈ 15
dakika CPU**. GPU'da çok daha hızlı ama ölçülmedi.

Arayüzde ilerleme göstermek için işlenen saniye / toplam saniye oranı
yazılmalı — 15 dakika boyunca "işleniyor" demek yetmez.

---

## 6. Aşama D — WebVTT indirme ve gömme

`klip_altyazilari` ve `video_altyazilari` göreli zaman tuttuğu için ikisi de
doğrudan üretilebiliyor.

### İndirme

```
GET /api/clips/{id}/altyazi.vtt?dil=tr
GET /api/videos/{id}/altyazi.vtt?dil=tr
```

```
WEBVTT

00:00:12.500 --> 00:00:15.000
Terörsüz Türkiye için yasal zemin oluştu
```

### Gömme (isteğe bağlı)

ffmpeg ile altyazı izini mp4'e **yumuşak** (soft) olarak eklemek:

```
ffmpeg -i klip.mp4 -i altyazi.vtt -c copy -c:s mov_text -metadata:s:s:0 language=tur ...
```

**Görüntüye basmak (hardsub) önerilmiyor:** yeniden kodlama gerektiriyor
(kalite kaybı + CPU) ve dil seçimini imkânsız kılıyor. Yumuşak altyazı
oynatıcıda açılıp kapanabiliyor.

---

## 7. Öncelik: canlı asla aç kalmamalı

`docs/faz5-altyazi-plani.md` §8 bunu zaten yazıyor:

> Canlı önceliklidir. Video altyazısı GPU'yu doldurup canlı yayını
> geciktirmemeli.

Bu, Aşama C'nin **en kritik kısıtı**. Canlı altyazının şu anki durumu zaten
kırılgan: ölçülen kapsama bütçesi 8-10 saniye ve `SubtitleLagMetrics` bunu
izliyor. Toplu iş araya girip GPU'yu doldurursa canlı altyazı sessizce
kaybolur — geç kalan altyazı gösterilmez, hiçbir hata da vermez.

### Öneri

| Yol | Not |
|---|---|
| **Ayrı `stt-worker` örneği** | En temizi. Toplu iş kendi GPU'sunu ya da CPU'yu kullanır, canlıya hiç dokunmaz |
| Kuyrukta öncelik | Tek örnekte iki kuyruk; canlı boşsa toplu iş alınır |
| Eşzamanlılık sınırı | `STT_MAX_CONCURRENCY`'nin bir kısmı toplu işe ayrılır |

Birincisi öneriliyor. `stt-worker` zaten ayrı bir servis; ikinci bir örnek
açmak yapılandırma işi.

**Ölçüm gerekli:** `SubtitleLagMetrics` kapsama oranı, toplu iş çalışırken ve
çalışmazken karşılaştırılmalı. Düşüyorsa ayrım yeterli değil.

---

## 8. Kapsam dışı

**Kaynak dilde altyazı.** Whisper `task=translate` ile çalışıyor ve orijinal
metni hiç üretmiyor — yalnızca İngilizce pivot ve ondan çeviriler var.
Kaynak dilde metin istenirse ikinci bir Whisper geçişi (`task=transcribe`)
gerekiyor; maliyet iki katı.

**Düzeltme arayüzü.** `faz5-altyazi-plani.md` 5.7'de duruyor, ayrı iş.

**Radyo altyazısı.** Radyolar da MediaMTX path'i ve sesleri var; hat aynen
çalışır. Ama istenmedi ve kapsama alınmadı.

---

## 9. Riskler ve açık sorular

| # | Konu | Etki |
|---|---|---|
| 1 | Toplu iş canlı altyazıyı aç bırakır | **Sessiz kayıp** — canlı altyazı görünmez olur, hata vermez. §7 |
| 2 | Aşama A yapılıp B yapılmazsa | Klip altyazısı kanal silinince kaybolur; kullanıcı "vardı, gitti" der |
| 3 | Mevcut klipler için altyazı yok | Geriye dönük üretim, kaynak altyazılar silinmişse Aşama C hattını gerektirir |
| 4 | Uzun video işlemesi | 1 saatlik video ≈ 15 dk CPU; ilerleme göstergesi olmadan "asıldı" sanılır |
| 5 | GPU'da STT hızı ölçülmedi | Aşama C'nin kapasite planı buna bağlı |

### Açık sorular

1. **Video altyazısı varsayılan açık mı olmalı?** Öneri: kapalı, elle
   tetiklenir. Her yüklenen videoyu çözümlemek çoğu durumda israf.
2. **Klip altyazısı hangi dillerde kopyalansın?** `metinler` JSONB'si zaten
   dördünü birden taşıyor; hepsini kopyalamak ek maliyet getirmiyor.
3. **WebVTT gömme isteniyor mu**, yoksa ayrı dosya indirme yeter mi?

---

## Sıra ve bağımlılık

| Aşama | İçerik | Bağımlılık | Büyüklük |
|---|---|---|---|
| **A** | Geriye sarma + klipte gösterim | — | küçük, yalnızca ön yüz |
| **B** | Klip altyazısını kopyala | A anlamlı kılıyor | orta, V22 + ClipWorker |
| **C** | Video altyazısı üretimi | — (A/B'den bağımsız) | **büyük**, yeni işçi + V23 |
| **D** | WebVTT indirme / gömme | B ya da C | küçük |

**A ile başlamak öneriliyor:** yeni veri üretmiyor, mevcut ve görünmeyen bir
şeyi görünür kılıyor. Tek başına bile "klipte altyazı yok" şikâyetini
çözüyor — yalnızca kanal silinene kadar, onu da B kalıcılaştırıyor.
