package org.example.etkinlik.dto;

/**
 * İsim bazlı gruplama sonucu — {@code TopEtiketDto}'nun aksine gerçek bir
 * {@code UUID} yok (örn. klip olayının hedefi kanal değil klibin kendisi;
 * kanal adı yalnızca {@code detay} JSON'unda ham metin olarak duruyor).
 */
public record AdSayiDto(String ad, long sayi) {
}
