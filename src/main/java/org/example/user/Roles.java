package org.example.user;

import java.util.Collection;
import java.util.List;

/**
 * Rol isimleri. Keycloak realm rolleri ile {@code roles} tablosundaki kayıtlar
 * aynı isimleri taşır — biri değişirse diğeri de değişmeli. Rol adı bir yerde
 * string olarak geçtiğinde buradan alınmalı.
 *
 * <p>Buradaki yazımlar üç yerde birebir aynı olmak zorunda: bu sabitler,
 * {@code roles.name} sütunu ve Keycloak realm rolünün adı. Biri farklı
 * yazılırsa rol ataması "rol tanımlı değil" hatası verir, yetki kontrolü de
 * sessizce başarısız olur.
 */
public final class Roles {

    /** Yönetici — kullanıcı ekler/siler, rol atar, şifre sıfırlar. */
    public static final String YONETICI = "Yönetici";

    /** Moderatör — yayın/kanal yönetir, kullanıcı yönetemez. */
    public static final String MODERATOR = "Moderatör";

    /** İzleyici — salt okuma. */
    public static final String IZLEYICI = "İzleyici";

    /** Yetki gücüne göre azalan sıra. {@link #effective} bu sırayı kullanır. */
    private static final List<String> BY_PRECEDENCE = List.of(YONETICI, MODERATOR, IZLEYICI);

    /**
     * {@code users.role_id} tek bir role işaret ediyor, oysa Keycloak bir
     * kullanıcıya birden fazla realm rolü atanmasına izin verir (özellikle
     * yönetici Keycloak konsolundan elle müdahale ettiğinde). Bu yüzden yerel
     * tabloya yazarken en yüksek yetkili rolü seçiyoruz — daha düşüğünü seçmek
     * kullanıcının gerçekte sahip olduğu yetkiyi gizlerdi.
     *
     * <p>Hiç uygulama rolü yoksa {@link #IZLEYICI} döner: {@code role_id} NOT NULL
     * olduğu için bir değer şart ve en az yetkili olan güvenli varsayılandır.
     * Bu yalnızca <b>gösterim/kayıt</b> içindir; gerçek yetki kontrolü her zaman
     * token'daki rollere bakan {@code @RolesAllowed} ile yapılır.
     */
    public static String effective(Collection<String> roleNames) {
        return BY_PRECEDENCE.stream()
            .filter(roleNames::contains)
            .findFirst()
            .orElse(IZLEYICI);
    }

    /** Verilen ismin uygulamanın tanıdığı rollerden biri olup olmadığı. */
    public static boolean isKnown(String roleName) {
        return BY_PRECEDENCE.contains(roleName);
    }

    /** Tanımlı tüm roller, yetki gücüne göre azalan sırada. */
    public static List<String> all() {
        return BY_PRECEDENCE;
    }

    private Roles() {
    }
}
