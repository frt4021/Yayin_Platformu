package org.example.subtitle.dto;

/**
 * Bir klip/video için hazır WebVTT altyazı parçası — {@code <track>}
 * elementine doğrudan verilebilir. Görünen ad (Türkçe/English/...) burada
 * YOK — frontend bunu {@code lang} koduyla zaten sahip olduğu
 * {@code SubtitleOverlay.SUBTITLE_LANGS} eşlemesinden çıkarıyor; aynı
 * metni iki yerde tutmamak için.
 */
public record SubtitleTrackDto(String lang, String url) {
}
