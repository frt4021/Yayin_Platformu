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
