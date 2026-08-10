# Faz 5 — Canlı Altyazı ve Çeviri

Durum: **planlama**. Kod yazılmadı.

## Gereksinimler (netleşmiş)

| | |
|---|---|
| Kaynak dil | **otomatik tespit** — Whisper'ın desteklediği 99 dil |
| Hedef diller | Türkçe · İngilizce · Almanca · **Rusça** |
| Ses dışarı çıkabilir mi | **hayır** — bulut STT/çeviri eleniyor |
| Eşzamanlı kanal | **16**, hepsinde anlık |
| Motor | **faster-whisper** |
| Kapsam | canlı yayın **+ yüklenen videolar** |
| Arşiv | evet — geriye sarmada ve kliplerde altyazı |
| Kalite | **yayına basılabilir** |

Bu birleşim tek bir sonucu dayatıyor: **ayrılmış GPU donanımı zorunlu.** Gerekçe
aşağıda sayılarla.

---

## 1. Ölçülen gerçekler

| | Değer |
|---|---|
| Ses kaynağı | `rtsp://mediamtx:8554/<path>` — AAC 44,1 kHz stereo |
| STT girdisine dönüşüm | 16 kHz mono PCM, **32 KB/sn** |
| Dönüşüm maliyeti | ihmal edilebilir |
| **Bu makine** | 8 çekirdek · 15 GB RAM · **GPU yok** |

Ses hattı hazır. Bu makinede faz **çalıştırılamaz** — geliştirme yapılabilir,
16 kanal üretimi yapılamaz.

---

## 2. Gecikme bütçesi

İlk sezgi "STT yavaş, altyazı geç kalır" olur. Yanlış — izleyici zaten geride:

```
Kaynak ──1-2 sn──► MediaMTX ──6-12 sn──► İzleyicinin ekranı
                       │
                       └──0 sn──► STT (2-8 sn) ──► Çeviri (0,1-0,5 sn)
                                                        │
                                              altyazı ~3-9 sn'de hazır
```

Altyazı, izleyicinin gördüğü kareden **önce** hazır oluyor. Asıl iş
hızlandırmak değil, **doğru kareyle eşleştirmek** — `PROGRAM-DATE-TIME` ile.
"Şimdi geldi, şimdi göster" altyazıyı 6-12 saniye erken gösterirdi.

---

## 3. Donanım: 16 kanal ne demek

Klip üretiminde iş bitince kaynak boşalıyordu. Altyazıda öyle değil — **kanal
yayındaysa STT sürekli çalışır.** 16 kanal, kesintisiz 16 kat gerçek zaman
çözümleme demek.

### Model seçimi kaliteyle bağlı

"Yayına basılabilir" isteniyorsa `large-v3` gerekiyor. `small` ve altı, Türkçe
ve Rusça'da özel isim ve sayılarda belirgin hata veriyor — düzeltme emeği
kazancı yer.

### Kaba büyüklük sırası

> Aşağıdaki sayılar **literatürden**, bu projede ölçülmedi. Kart seçilmeden önce
> gerçek donanımda doğrulanmalı (aşama 5.0).

| Donanım | `large-v3` gerçek zaman katı |
|---|---|
| 8 çekirdek CPU | ~0,3-0,5× — **kullanılamaz** |
| RTX 4060 Ti 16 GB | ~8-12× |
| RTX 4090 | ~20-40× (yığın halinde) |

### Gereken kapasite

```
16 kanal kesintisiz                        →  16× gerçek zaman
VAD ile sessizlik atlanınca (~%65 konuşma) →  ~10-11× gerçek zaman
Ani yükler + çeviri payı                   →  hedef ~20× gerçek zaman
```

**Sonuç:** tek bir RTX 4090 sınıfı kart, ya da iki orta sınıf kart. Tek bir
4060 Ti 16 kanalı taşımaz.

VRAM bağlayıcı kısıt değil: `large-v3` float16 ≈ 3,1 GB ve **model tek örnek,
tüm kanallar onu paylaşır.** Sınırlayan şey hesap gücü.

---

## 4. Optimizasyon — fazın asıl işi

"16 kanal" hedefi, mimarinin her katmanında optimizasyon gerektiriyor. Sırayla,
kazanç büyüklüğüne göre:

### 4.1 VAD — en büyük tek kazanç

Sessizlik ve müzik bölümlerinde STT çalıştırmak boşa yanan GPU. Tipik yayında
konuşma oranı %60-70. **Doğrudan üçte bir tasarruf**, kalite kaybı sıfır.

Silero-VAD CPU'da çalışır, GPU'yu hiç meşgul etmez.

### 4.2 Yığın (batch) çözümleme

16 kanalın pencereleri tek tek gönderilirse GPU sürekli boşta bekler. Pencereler
toplanıp **tek yığında** çözülmeli. faster-whisper'ın `BatchedInferencePipeline`
desteği tam bu iş için.

Kazanç 2-4 kat — 16 kanal hedefini mümkün kılan tek şey bu olabilir.

### 4.3 Nicemleme (quantization)

`float16` yerine `int8_float16`: bellek yarıya, hız ~%30 artıyor, kalite kaybı
Türkçe'de ölçülebilir sınırın altında. **Ölçülmeli**, varsayılmamalı.

### 4.4 Çeviriyi GPU'dan çıkarmak

Çeviri metin üzerinde çalışır ve cümleler kısa. GPU'yu STT'ye bırakıp çeviriyi
**CPU'da** koşturmak, 8 çekirdeği boşta duran bir makinede bedava kapasite.

### 4.5 Kanal başına değil, dil başına

Altyazı **izleyiciye özel değil**. Bir kanalın Almanca altyazısı bir kez
üretilir, 50 izleyici aynısını görür. 16 kanal × 4 dil = 64 metin akışı — ama
metin ucuz, üretim tek.

---

## 5. Dil tespiti ve İngilizce pivot

Kaynak dil **bilinmiyor ve sınırlanmıyor**. Whisper'ın 99 dil desteği tam bu
işe kullanılıyor: hangi dil gelirse gelsin tek geçişte İngilizce'ye çevriliyor.

```
ses ──► Whisper (task=translate) ──► İngilizce metin
                 │                        │
                 └─ tespit edilen dil     ├──► Türkçe
                    (üstveri olarak       ├──► Almanca
                     saklanıyor)          └──► Rusça
```

### Bunun getirdiği sadelik

Kaynak keyfî olduğu halde **model kombinasyonu patlamıyor**, çünkü kaynak
tarafını Whisper hallediyor. Metin çevirisinde yalnızca İngilizce'den çıkan
üç yön kalıyor — çevrimdışı kısıt altında bu belirleyici bir kazanç: imaja
gömülecek çeviri modeli sayısı sabit ve küçük.

Yeni bir dilde kanal eklendiğinde **hiçbir şey değişmiyor**; Whisper onu da
İngilizce'ye çeviriyor.

### Tespit neden yine de gerekli

`task=translate` dili kendi buluyor, ama tespit sonucu **saklanmalı**:

- kullanıcıya "bu yayın Rusça" bilgisi gösterilecek,
- yanlış tespit şüphesinde elle geçersiz kılma gerekecek,
- ileride kaynak dilde altyazı istenirse hangi kanalların hangi dilde olduğu
  bilinmeden planlanamaz.

Pencere bazlı tespit **titrek**: müzik veya sessizlik anında yanlış dil seçer.
İlk 30 saniyeden tespit edip kanala **sabitlemek**, periyodik doğrulamak ve
elle geçersiz kılma bırakmak gerekiyor.

### Bilinçli takas

Kaynak dil hedef dillerden biriyse (örn. Türkçe yayın), Türkçe altyazı
`TR → EN → TR` yolundan geliyor. Gidiş dönüş çeviri, doğrudan
transkripsiyona göre özel isim ve sayılarda kayıp veriyor.

Karşılığında alınan: tek Whisper geçişi, üç çeviri modeli, kaynak dil
kısıtı yok. **Bu takas bilerek yapıldı.** Türkçe kalitesi yetersiz bulunursa
çözüm belli ve sonradan eklenebilir — tespit sonucu zaten saklandığı için
kaynak hedef dillerden biriyken `task=transcribe` dalına geçmek yeterli.

---

## 5.5 "Ses dışarı çıkamaz" — tam olarak ne getiriyor

Bu kısıt yalnızca bulut STT'yi elemiyor. Üç sonucu daha var ve ikisi kolayca
gözden kaçıyor.

### Metin de sestir

Altyazı sesin türevi. Bulut **çeviri** kullanmak, sesin içeriğini üçüncü tarafa
göndermek demek — ses dosyası gitmese bile. DeepL, Google Translate, OpenAI ve
benzeri tamamen eleniyor. Çeviri de yerelde koşacak.

### Modeller çalışma anında indirilemez

faster-whisper, Silero-VAD ve çeviri modelleri varsayılan olarak ilk
çalıştırmada HuggingFace'ten iner. Bu **sesi dışarı göndermez** ama:

- kapalı ağda kurulum **sessizce başarısız olur**,
- indirme sırasında ne indirildiği denetlenemez,
- sürüm sabitlenmezse iki kurulum farklı model kullanır.

Doğrusu: modeller **imaja gömülür**, çalışma anında ağ gerekmez.

Toplam model yükü **~3,4 GB** (dökümü bölüm 6'da). Bu bir bedel ama
alternatifi, kapalı ağda hiç başlamayan bir kurulum.

`HF_HUB_OFFLINE=1` ve `TRANSFORMERS_OFFLINE=1` ayarlanmalı — model eksikse
sessizce indirmeye çalışmak yerine **açıkça patlasın**.

### Doğrulama, güvenmek yerine

Kapalılık iddia edilecekse ölçülmeli. Kabul testi: `altyazi-worker`
konteynerini dış ağa erişimsiz bir Docker ağına alıp 16 kanalla çalıştırmak.
Çalışıyorsa kısıt gerçekten sağlanıyor demektir.

Compose'da bu kalıcı hale getirilebilir — konteynere yalnızca iç ağ verilir,
dışarı çıkış yolu hiç olmaz.

---

## 6. Çeviri mimarisi

Whisper pivotu sağladığı için metin çevirisinde yalnızca **İngilizce'den
çıkan** yönler kalıyor.

### Model seti: 3 model

| Yön | Model | Boyut |
|---|---|---|
| EN → TR | `Helsinki-NLP/opus-mt-en-tr` | ~300 MB |
| EN → DE | `Helsinki-NLP/opus-mt-en-de` | ~300 MB |
| EN → RU | `Helsinki-NLP/opus-mt-en-ru` | ~300 MB |

**Toplam ~900 MB.** Kaynak tarafı Whisper'da çözüldüğü için `X → EN` modeli
hiç gerekmiyor — kaynak dil kümesi genişlese bile bu set sabit kalıyor.

### Akış

```
ses ──► Whisper task=translate ──► EN metin ──┬──► TR
        (kaynak dil ne olursa)     (altyazı)  ├──► DE
                                              └──► RU
```

İngilizce **çeviri istemiyor** — Whisper'ın çıktısı zaten o. Yani kanal
başına **3 metin çevirisi**, sabit.

### Yük

```
20 kanal × 3 çeviri = 60 metin akışı
```

Metin ucuz: cümle başına birkaç on milisaniye, CPU'da. 8 çekirdek bunu rahat
taşıyor ve **GPU'ya hiç dokunulmuyor** — 20 kanal hedefinde GPU en kıt kaynak,
onu tamamen Whisper'a bırakmak doğru takas.

### İmaj boyutu

```
faster-whisper large-v3 (int8_float16)   ~1,6 GB
Opus-MT × 3                              ~0,9 GB
CUDA çalışma zamanı                      ~2-3 GB
────────────────────────────────────────────────
stt-worker imajı                         ~5-6 GB
```

Modeller **imaja gömülü**, çalışma anında indirilmiyor. Bu yüzden ayrı servis:
`video-worker`'ı 6 GB yapmanın anlamı yok ve GPU yalnızca buraya gerekiyor.

---

## 7. Kurgu

```
mediamtx ──RTSP(ses)──► altyazi-worker ──► Postgres ──► backend ──WS──► tarayıcı
                          │                    ▲
                          ├─ ffmpeg → 16 kHz mono PCM
                          ├─ Silero-VAD (CPU)              Redis: bildirim
                          ├─ faster-whisper (GPU, yığın)
                          └─ Opus-MT (CPU, pivot)
```

**Ayrı konteyner.** Video işleri anlık ve kuyruklu, altyazı sürekli ve kanala
bağlı. Aynı konteynerde biri diğerini aç bırakırdı.

**Doğruluk kaynağı veritabanı, Redis yalnızca bildirim** — klip hattındaki
desenin aynısı.

### Veri modeli (arşiv baştan kurgulanıyor)

```
altyazi_parcalari
  id, channel_id, dil, baslangic (PDT), bitis (PDT),
  metin, kesinlesti, guven, kaynak_mi
```

Zaman damgası **mutlak (PDT)**. Böylece:

- canlı izleyici `currentTime` → PDT dönüşümüyle eşleştirir,
- geriye sarmada aynı sorgu çalışır,
- klip alındığında altyazı **aralık sorgusuyla** gelir — ayrı iş gerekmez,
- video kütüphanesi için aynı tablo, `video_id` ile.

Sonradan eklemek pahalı olurdu; baştan böyle kurulmalı.

---

## 8. Videolara altyazı

Canlıdan **daha kolay**: gerçek zaman kısıtı yok, mevcut kuyruk deseni aynen
kullanılabilir (`videolar` → iş → işçi → sonuç).

Tek dikkat: **canlı önceliklidir.** Video altyazısı GPU'yu doldurup canlı
yayının önüne geçmemeli. Kuyrukta ayrı öncelik sınıfı gerekiyor.

Çıktı WebVTT olarak da saklanmalı — indirilebilir altyazı dosyası beklenen bir
özellik.

---

## 9. "Yayına basılabilir" ne getiriyor

Bu beklenti, teknik hattın ötesinde iş gerektiriyor:

- **Kesinleşme mantığı.** Whisper yeni ses geldikçe önceki kelimeleri düzeltir;
  ekranda yazı titrer. Bir kelime ancak N pencere değişmediyse "kesinleşmiş"
  sayılmalı.
- **Pencere sınırı birleştirme.** Örtüşmeli kayan pencere (örn. 8 sn pencere,
  2 sn örtüşme) ve örtüşen bölgede birleştirme. Fazın en çok zaman alacak
  kısmı.
- **Düzeltme arayüzü.** Arşivlenen altyazı yayına basılacaksa insan gözünden
  geçmeli. Metin düzenleme ve zaman kaydırma ekranı gerekiyor.
- **Terim sözlüğü.** Özel isimler, kurum adları, teknik terimler. Whisper'ın
  `initial_prompt` alanı kanala özel sözlükle beslenebilir — ölçülebilir kalite
  kazancı.

---

## 10. Aşamalar

| Aşama | İş | Çıktı |
|---|---|---|
| **5.0** | **Ölçüm**: gerçek GPU'da `large-v3` + yığın + int8, gerçek zaman katı | **kart kararı** |
| 5.1 | `altyazi-worker`, ses çekme + Silero-VAD | konuşma bölütleri |
| 5.2 | faster-whisper, yığın çözümleme, kayan pencere + kesinleşme | kararlı metin |
| 5.3 | `altyazi_parcalari` tablosu + Redis + WS ucu | tarayıcıya ulaşır |
| 5.4 | Oynatıcıda PDT eşleme, dil seçici | senkron altyazı |
| 5.5 | Dil tespiti (4 dille sınırlı) + pivotlu çeviri (CPU); pivot ↔ doğrudan kalite ölçümü | 4 dil |
| 5.6 | Video kütüphanesi altyazısı, WebVTT indirme | toplu iş |
| 5.7 | Düzeltme arayüzü, terim sözlüğü | yayına basılabilir |
| 5.8 | GPU/CPU anahtarı, geriye sarmada altyazı | tamamlanmış |

**5.0 atlanamaz.** Kart alınmadan ölçüm yapılamaz, ölçüm yapılmadan kart
seçilemez — bu döngü, ödünç/kiralık bir GPU'da tek günlük bir testle kırılır.
Aksi halde faz ortasında "bu kart yetmiyor" ile karşılaşılır.

---

## 11. Açık kalan riskler

**Bu makinede geliştirilebilir, üretilemez.** GPU yok. 1-2 kanalla CPU'da
`small` ile hat kurulabilir; 16 kanal `large-v3` yalnızca hedef donanımda
denenebilir.

**16 kanal üst sınır, ortalama değil.** Hepsi aynı anda konuşuyorsa VAD kazancı
düşer. Kart seçimi en kötü senaryoya göre yapılmalı.

**Dört dil dışı içerik.** Aday listesi TR/EN/DE/AR ile sınırlandığı için
başka bir dilde konuşma duyulursa en yakın adaya zorlanır ve anlamsız metin
üretir. Bilinçli takas: karışan çift sayısı azaldığı için dört dilde tespit
belirgin şekilde daha güvenilir.

**Kiril alfabesi.** Rusça altyazı için oynatıcının fontu Kiril desteklemeli.
Arapça kapsamdan çıktığı için sağdan sola yazım sorunu da kalktı — ama hedef
dil kümesi ileride tekrar değişirse bu ikisi baştan gözden geçirilmeli.

**Gidiş dönüş çeviri — bilinçli takas.** Türkçe yayında Türkçe altyazı
`TR → EN → TR` yolundan geliyor; özel isim ve sayılarda kayıp bekleniyor.
Ölçülmeli: birkaç Türkçe bölütü hem bu yoldan hem doğrudan transkripsiyonla
üretip karşılaştırmak yeterli. Kabul edilemez çıkarsa çözüm hazır — tespit
sonucu saklandığı için kaynak hedef dillerden biriyken `task=transcribe`
dalına geçmek yetiyor (bölüm 5).

**Konuşmacı örtüşmesi.** Canlı yayında müzik, alkış, aynı anda konuşan iki kişi.
Doğruluk beklentisi laboratuvar kaydına göre değil, bu koşullara göre
kurulmalı.
