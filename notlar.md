# Notlar — Ertelenen İşler

Sonra ele alınacak konular. Her madde, karar verilirken gereken bağlamı
yanında taşıyor; ay sonra açıldığında baştan araştırma gerekmesin.

---

## 1. Kaynak çözünürlüğü doğrulanmıyor — merdiven kaynağın üstüne çıkabiliyor

**Durum:** Açık. 2026-08-04 tarihinde konuşuldu, sonraya bırakıldı.

### Sorun

Çözünürlük merdiveni **her zaman aşağı doğru** olmalı: kaynağın vermediği
ayrıntı üretilemez. 360p bir kaynağa 1080p rendition eklenirse ffmpeg
`scale=1920:1080` ile büyütmeyi gerçekten yapar, path yayına girer ve
görüntü 1080p etiketli olur — ama içerik 360p'nin bulanık büyütülmüşüdür.

Bu kural `Rendition` javadoc'unda yazılı bir **konvansiyon**; doğrulama yok.
`Rendition.parse` herhangi bir boyutu kabul ediyor, `ChannelService.normalize`
yalnızca biçimi kontrol ediyor. Hatalı giriş sessizce geçiyor ve yalnızca
GPU/bant genişliği faturasında görünüyor.

### Neden şu an doğrulanamıyor

Backend kaynağın çözünürlüğünü bilmiyor. MediaMTX'in `/v3/paths/list` yanıtı
(`MediaMtxPathList.Item`) `tracks` alanında yalnızca kodek adlarını veriyor —
`H264`, `MPEG-4 Audio` gibi. Çözünürlük yok.

### Olası çözüm

Kanal kaydedilirken kaynağa `ffprobe` çekip gerçek çözünürlüğü öğrenmek,
merdivendeki hedefleri bunun üstündeyse reddetmek ya da uyarmak.

Karar verilecek noktalar:

- **Reddetmek mi, uyarmak mı.** Reddetmek, kaynağı o an erişilemeyen bir
  kanalın kaydedilmesini de engelleyebilir — `ffprobe` başarısız olduğunda
  ne yapılacağı ayrıca kararlaştırılmalı. Sessizce geçmek muhtemelen doğru:
  doğrulayamamak, geçersiz demek değil.
- **Nerede çalışacağı.** `ffprobe` backend konteynerinde yok; ya imaja
  eklenecek ya da MediaMTX konteynerinde çalıştırılacak.
- **Maliyeti.** Kaydetme isteğini kaynağa bağlanma süresi kadar geciktirir.
  Erişilemeyen bir kaynakta bu zaman aşımı süresi demek.

### Bağlam: kaynaktan yüksek çözünürlük neden istenmemeli

Tarayıcı zaten büyütme yapıyor — 360p yayın tam ekran açıldığında oynatıcı
görüntüyü ekran çözünürlüğüne ölçekliyor. Sunucuda büyütmek aynı işi önceden
yapmaktan ibaret, sonuç aynı. Farkı olan tek şey maliyet:

| | 360p kaynak, olduğu gibi | 360p kaynak, 1080p'ye büyütülmüş |
|---|---|---|
| İzleyici başına bant genişliği | ~500k | ~4000k (8 kat) |
| GPU | yok | rendition başına ~%14 CPU |
| Görüntü | orijinal | yeniden kodlandığı için bir miktar **daha kötü** |

Üç kalemde de kayıp. Gerçek çözüm kaynakta: kanalın `sourceUrl`'indeki
cihazın yüksek çözünürlük göndermesi gerekiyor.

---

## 2. DVR'da "Kaynak" seçimi çalışmıyor — sessizce 720p'ye düşüyor

**Durum:** Açık. Hata, yukarıdaki konu konuşulurken fark edildi.

### Sorun

Kanal formundaki "Kayıt çözünürlüğü" alanında **Kaynak (en yüksek)**
seçilirse kayıt yine de 720p'den alınıyor — merdivende 720p varsa.
Kullanıcıya hiçbir uyarı çıkmıyor.

1080p'lik bir kaynakta bu, kaydın yarı çözünürlükte tutulması demek.

### Sebep

Boş değere iki farklı anlam yüklenmiş: *"kullanıcı tercih belirtmedi,
varsayılanı uygula"* ve *"kullanıcı açıkça kaynağı seçti"*.

`ChannelFormDialog.tsx:209` — "Kaynak" seçeneği boş string gönderiyor:

```tsx
<option value="">Kaynak (en yüksek)</option>
```

`ChannelService.resolveDvrRendition()` (satır 229) — boş değeri
"tercih belirtilmedi" sayıp varsayılana düşüyor:

```java
String wanted = (requested == null || requested.isBlank())
    ? DEFAULT_DVR_RENDITION   // "720p"
    : requested.trim();
return ladder.stream().anyMatch(r -> r.suffix().equals(wanted)) ? wanted : "";
```

### Olası çözümler

- Arayüzün açık bir işaret göndermesi (`"source"` gibi) ve backend'in bunu
  boştan ayırması. Sözleşme değişikliği; `UpdateChannelRequest` alanı
  `@NotNull` olduğu için istemcilerin hepsi güncellenmeli.
- Ya da varsayılanın yalnızca kanal **ilk oluşturulurken** uygulanması,
  güncellemede kullanıcının gönderdiğine dokunulmaması.

İkincisi daha küçük bir değişiklik ama oluşturma ekranında aynı sorun kalır.

---

## 3. `channels.renditions` ayarı ölü

**Durum:** Açık. Silinmesi önerildi, karar bekliyor.

`application.properties:67`:

```properties
channels.renditions=${CHANNELS_RENDITIONS:}
```

Hiçbir yere enjekte edilmiyor — `channel` paketinde yalnızca
`mediamtx.hls-base-url` ve `channels.max-active` için `@ConfigProperty` var.

`V6__kanal_renditionlari.sql` merdiveni global ayardan kanal bazına
taşıdığında geride kalmış. Üstünde hâlâ 20 satırlık açıklayıcı yorum
duruyor; okuyan birinin "merdiven buradan ayarlanıyor" sanmasına açık.

Yorumdaki ölçüm bilgisi (VAAPI %14'e karşı libx264 %142) değerli — silinirken
`Rendition` ya da `TranscodeCommand` javadoc'una taşınmalı. Zaten
`TranscodeCommand.VAAPI_DEVICE` üstünde benzer bir not var, orada birleşebilir.

---

## 5. MediaMTX sessizce takılıp kalıyor — kendiliğinden toparlanmıyor

**Durum:** Açık. 2026-08-04'te yaşandı ve elle yeniden başlatılarak çözüldü.
**Kök sebep bulunamadı.**

### Yaşanan

MediaMTX konteyneri çalışmaya devam ederken kanalları çekmeyi bıraktı ve
~2 saat 45 dakika boyunca hiç toparlanmadı. Son satır:

```
08:52:57 ERR [path kanal1] [HLS source] context deadline exceeded
                                        (… while reading body)
08:52:57 INF [path kanal1] runOnAvailable command stopped
```

Ondan sonra kanal1/kanal2 için **tek bir bağlanma denemesi bile
loglanmadı**. Bu asıl anormallik: `sourceOnDemand: false` iken MediaMTX
başarısız kaynağı ~5 saniyede bir yeniden denemeli ve her denemeyi
loglamalı (test sırasında sahte bir kaynakla bu davranış birebir görüldü).

Durum tespiti:

| Gösterge | Değer |
|---|---|
| Konteyner | çalışıyor, RestartCount 0 |
| CPU | %0.02 |
| Path'ler | duruyor, `ready=false`, `rx=0` |
| Path config | `source` doğru, `sourceOnDemand: false` |
| API | yanıt veriyor, config reload kabul ediyor |

### Elenenler

- **Ağ değil:** konteynerin içinden DNS çözülüyor, kaynaklar HTTP 200
  dönüyor, master + varyant + segment zinciri baştan sona çekiliyor.
- **Disk değil:** %52 dolu, 432 GB boş.
- **Yapılandırma değil:** path config'i elle okundu, doğruydu.

Yeniden başlatma her şeyi anında düzeltti.

### Dikkat: log saatleri UTC, sistem UTC+3

Konteyner logları UTC yazıyor, `journalctl` ve `who -b` yerel saat veriyor.
Arıza anı log'da `08:52:57` ise sistem günlüğünde **11:52:57** aranmalı.
İlk incelemede yanlış pencereye bakıldı; tetikleyici (ağ kesintisi, uyku,
kaynak tarafında bir olay) bu yüzden hâlâ bilinmiyor.

### Yapılması gereken

Kök sebep bulunsa da bulunmasa da sistem bundan **kendiliğinden çıkabilmeli**.
İki saat boyunca kimse fark etmedi; arayüz "Akmıyor" gösteriyordu ama bunu
gören biri olmadıkça durum düzelmiyor.

Öneri: zamanlanmış bir gözcü. Altyapı hazır — `MediaMtxService.pathStates()`
anlık durumu, `ChannelService.restoreActiveChannels()` yeniden yazmayı zaten
yapıyor. Kural: bir kanal/radyo `active` iken N dakikadır `ready=false` ise
path'i yeniden uygula, düzelmiyorsa uyar.

Karar verilecekler: eşik süresi; kaç denemeden sonra vazgeçilecek (kaynağı
gerçekten ölü bir kanalda sonsuza kadar denemek log'u boğar); uyarının nereye
gideceği. Compose'da `mediamtx` için `healthcheck` de yok — ama süreç
çökmediği, yalnızca çekmeyi bıraktığı için düz bir HTTP healthcheck'i bu
arızayı yakalamaz; kontrol path hazırlığına bakmalı.

---

## 6. TRT kanalının kaynağı master.m3u8 olmamalı — 1080p+ MediaMTX'i kırıyor

**Durum:** ACİL SAYILIR. Düzeltme şu an yalnızca MediaMTX belleğinde;
veritabanı hâlâ eski değeri taşıyor.

### Sorun

`kanal2` (trthaber) kaynağı `https://tv-trthaber.medya.trt.com.tr/master.m3u8`.
MediaMTX master playlist'ten **en yüksek bant genişliğini** seçiyor — TRT'de
bu 2560x1440 / 11.5 Mbps. O varyantın segmentleri MediaMTX'in HLS
okuyucusundaki sınırı aşıyor ve kanal hiç yayına girmiyor:

```
ERR [path kanal2] [HLS source] max recorded size exceeded
```

Varyantlar tek tek denendi:

| Varyant | Segment (6 sn) | Sonuç |
|---|---|---|
| master_360 | 0.39 MB | — |
| master_480 | 0.85 MB | — |
| **master_720** | **3.01 MB** | **çalışıyor** |
| master_1080 | 4.29 MB | `max recorded size exceeded` |
| master_1440 | 7.23 MB | `max recorded size exceeded` |

Sınır 3.01 MB ile 4.29 MB arasında. kanal1 (DW) bu sorunu yaşamıyor çünkü
en yüksek varyantı 480x270.

### Yapılması gereken

Kanal kaydındaki `source_url` **`master_720.m3u8`** olarak değiştirilmeli.
Şu an yalnızca MediaMTX'e patch'lendi; **backend bir daha yeniden başlarsa
`ChannelRestorer` eski `master.m3u8` değerini geri yazar ve kanal2 sessizce
yeniden ölür.**

720p seçmek kayıp değil: merdiven zaten 720p'de bitiyor ve DVR de 720p'den
kaydediyor. Üstelik kaynaktan 11.5 Mbps yerine 4.7 Mbps çekilir. 1080p
istense bile MediaMTX okuyamadığı için şu an mümkün değil.

Genel ders: master playlist vermek, MediaMTX'in en pahalı varyantı seçmesi
demek. [[1. Kaynak çözünürlüğü doğrulanmıyor]] maddesiyle aynı aile —
kaynak seçimi denetlenmiyor.

---

## 4. `/q/health` 500 dönüyor

**Durum:** Açık. Düşük öncelik.

`smallrye-health` extension'ı kurulu değil (`Installed features` listesinde
yok) ama `application.properties`'te `quarkus.smallrye-health.root-path`
ayarı duruyor. Quarkus açılışta uyarıyor:

```
WARN [io.quarkus.config] Unrecognized configuration key
"quarkus.smallrye-health.root-path" was provided; it will be ignored
```

Tanımsız route'a düşen istek uygulamanın genel hata eşleyicisine takılıp
500 üretiyor — yani "sağlıksız" değil, "böyle bir uç yok" demek.

İki seçenek: `quarkus-smallrye-health` bağımlılığını eklemek, ya da kullanmaya
niyet yoksa ayarı silmek. Compose'da `mediamtx`, `postgres` ve `minio` için
`healthcheck` tanımlı ama backend'de yok — eklenirse bu ucun çalışması gerekir.
