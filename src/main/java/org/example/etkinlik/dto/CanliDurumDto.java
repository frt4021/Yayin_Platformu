package org.example.etkinlik.dto;

/**
 * @param anlikTrafikMbps MediaMTX'in {@code bytesReceived} sayaçlarından iki
 *                        örnekleme arasındaki farktan hesaplanıyor. İlk
 *                        çağrıda (henüz önceki örnek yokken) {@code null} —
 *                        sessizce sıfır gösterilmiyor, frontend "ölçülmüyor"
 *                        olarak işaretler.
 * @param yayindakiKanal  "Yayınlar" sağlık kartıyla AYNI hesap
 *                        ({@code AnalitikService.yayinSayilariniHesapla}) —
 *                        MediaMTX'e ulaşılamazsa {@code null}, sessizce sıfır
 *                        gösterilmez.
 */
public record CanliDurumDto(long esZamanliIzleyici, long esZamanliDinleyici,
                             long aktifDvrKaydi, Long anlikTrafikMbps, Long yayindakiKanal) {
}
