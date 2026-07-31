package org.example.clip;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * İmzalı adres üretmek için kullanılan MinIO istemcisini işaretler.
 *
 * <p>Gerçek bir niteleyici gerekiyor: {@code @Named} tek başına
 * {@code @Default} niteleyicisini kaldırmıyor, iki üretici de varsayılan
 * olarak kalıp {@code AmbiguousResolutionException} veriyor.
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
public @interface PresignClient {
}
