package org.example.video.dto;

/**
 * Süreli imzalı adresler; dosya doğrudan nesne depolamasından gelir.
 *
 * <p>Kliplerdeki {@code ClipLinks} ile aynı gerekçe: yönlendirme yerine JSON
 * dönülüyor çünkü tarayıcı CORS nedeniyle yönlendirme yanıtındaki
 * {@code Location} başlığını okuyamıyor.
 *
 * <p><b>Süre uyarısı:</b> {@code stream} adresinin ömrü videonun süresinden
 * uzun olmalı. Kısa kalırsa oynatma başlar ama kullanıcı süre dolduktan sonra
 * ileri sardığında yeni bir range isteği gider ve 403 alır — arayüzde bu,
 * videonun ortasında bozulması gibi görünür.
 *
 * @param stream   {@code <video src>} ile oynatılabilir
 * @param download tarayıcıyı dosyayı kaydetmeye zorlar
 * @param thumbnail küçük resim; kayıt {@code HAZIR} değilse null
 */
public record VideoLinks(
    String stream,
    String download,
    String thumbnail,
    String fileName
) {
}
