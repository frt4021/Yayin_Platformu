package org.example.user;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Token'dan kullanıcı bilgisi okumanın tek yeri.
 *
 * <p>Kullanıcı adı için {@code getPrincipal().getName()} yeterli değil:
 * Quarkus principal ismini sırayla {@code upn}, {@code preferred_username} ve
 * {@code sub} claim'lerinden seçiyor, yani realm ayarına göre isim yerine
 * UUID dönebiliyor. Keycloak Admin API'de kullanıcı adıyla iş yaptığımız
 * (şifre doğrulama) için claim'i açıkça okuyoruz.
 */
final class
TokenClaims {

    private static final String PREFERRED_USERNAME = "preferred_username";

    static String username(JsonWebToken jwt) {
        String preferred = jwt.getClaim(PREFERRED_USERNAME);
        return preferred != null ? preferred : jwt.getName();
    }

    static String username(SecurityIdentity identity) {
        return identity.getPrincipal() instanceof JsonWebToken jwt
            ? username(jwt)
            : identity.getPrincipal().getName();
    }

    /** OIDC dışı bir kimlikte (ör. temel kimlik doğrulama) {@code null} döner. */
    static String subject(SecurityIdentity identity) {
        return identity.getPrincipal() instanceof JsonWebToken jwt ? jwt.getSubject() : null;
    }

    private TokenClaims() {
    }
}
