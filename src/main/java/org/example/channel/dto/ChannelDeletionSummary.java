package org.example.channel.dto;

/**
 * Kanal silinmeden önce neyin gideceğinin dökümü.
 *
 * <p><b>Neden ayrı bir uç:</b> "Silmek istediğinize emin misiniz?" sorusu tek
 * başına hiçbir bilgi taşımıyor. Kullanıcının kararı verebilmesi için kaç
 * klibin, kaç ekran görüntüsünün ve ne kadar kaydın söz konusu olduğunu
 * görmesi gerekiyor — 3 klip ile 300 klip aynı soru değil.
 *
 * @param channelName    onay metninde göstermek için
 * @param clipCount      kanala bağlı klip sayısı
 * @param screenshotCount kanala bağlı ekran görüntüsü sayısı
 * @param dvrSegmentCount DVR segment sayısı
 * @param dvrHours       DVR kaydının toplam süresi (saat) — segment sayısı
 *                       kullanıcıya bir şey ifade etmiyor, süre ediyor
 * @param dvrBytes       DVR kaydının kapladığı yer
 * @param clipBytes      kliplerin kapladığı yer
 * @param streaming      kanal şu anda yayında mı — yayındaki bir kanalı
 *                       silmek büyük ihtimalle kazadır ve uyarılmalı
 */
public record ChannelDeletionSummary(
    String channelName,
    long clipCount,
    long screenshotCount,
    long dvrSegmentCount,
    double dvrHours,
    long dvrBytes,
    long clipBytes,
    boolean streaming
) {
}
