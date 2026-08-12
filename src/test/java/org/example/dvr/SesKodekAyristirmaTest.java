package org.example.dvr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DvrArchive.sesAacMi()} içindeki ffprobe çıktısı ayrıştırması.
 *
 * <p>Bu karar <b>sessiz ve ağır</b> bir hataya yol açıyor: yanlış çıkarsa
 * {@code aac_adtstoasc} filtresi eklenmiyor, ffmpeg AAC'yi MP4'e yazamayıp
 * hemen ölüyor ve geriye yalnızca <b>1276 baytlık fMP4 başlığı</b> kalıyor.
 * Klip "HAZIR" işaretleniyor ama boş — hiçbir yerde hata görünmüyor.
 *
 * <p>Yaşandı: birleştirilmiş TS'te ffprobe {@code "aac\naac"} bastı, tam
 * eşitlik arayan karşılaştırma {@code false} döndü ve üretilen bütün klipler
 * boş çıktı.
 */
class SesKodekAyristirmaTest {

    /** Üretimdeki mantığın aynısı — ilk satır alınıp karşılaştırılıyor. */
    private static boolean aacMi(String ffprobeCiktisi) {
        return "aac".equalsIgnoreCase(
            ffprobeCiktisi.lines().findFirst().orElse("").strip());
    }

    @Test
    void tekSatirAac() {
        assertTrue(aacMi("aac"));
        assertTrue(aacMi("aac\n"));
    }

    @Test
    void tekrarEdenSatirlar() {
        // REGRESYON. Birlestirilmis TS'te program bilgisi tekrar ettigi icin
        // ffprobe ayni izi birden cok kez basiyor.
        assertTrue(aacMi("aac\naac"));
        assertTrue(aacMi("aac\naac\naac\n"));
    }

    @Test
    void aacOlmayan() {
        assertFalse(aacMi("mp3"));
        assertFalse(aacMi("mp3\nmp3"));
        assertFalse(aacMi("ac3"));
    }

    @Test
    void bosCikti() {
        // ffprobe basarisiz oldu ya da ses izi yok. Filtre EKLENMEMELI:
        // yanlis filtre eklemek eksik filtreden kotu -- ilki ffmpeg'i hic
        // baslatmiyor, ikincisi yalnizca sesi bozuyor.
        assertFalse(aacMi(""));
        assertFalse(aacMi("\n"));
        assertFalse(aacMi("   "));
    }
}
