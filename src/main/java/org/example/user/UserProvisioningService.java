package org.example.user;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.example.user.entity.AppUser;
import org.example.user.entity.Role;
import org.jboss.logging.Logger;

import java.util.Set;

/**
 * Keycloak kullanıcısının yerel {@code users} satırını oluşturur/günceller.
 *
 * <p>Yönetici kullanıcı açma işini uygulama dışından — doğrudan Keycloak
 * konsolundan — da yapabiliyor. Böyle açılan bir kullanıcının yerel satırı
 * olmaz; uygulama verisi ona bağlanamayacağı için ilk işleminde patlardı.
 * Bu yüzden satır, kullanıcının ilk kimlikli isteğinde talep anında (just-in-time)
 * oluşturulur. Aynı mekanizma, rolü Keycloak'tan elle değiştirilen kullanıcının
 * yerel rolünü de günceller.
 */
@ApplicationScoped
public class UserProvisioningService {

    private static final Logger LOG = Logger.getLogger(UserProvisioningService.class);

    /**
     * Token sahibinin yerel kaydını güncel tutar. Kayıt zaten varsa ve
     * kullanıcı adı/rolü değişmemişse hiçbir yazma yapılmaz.
     */
    @Transactional
    public void syncFromIdentity(SecurityIdentity identity) {
        String keycloakId = TokenClaims.subject(identity);
        if (keycloakId == null) {
            // OIDC dışı bir kimlik (ör. test) — eşleştirecek bir Keycloak kullanıcısı yok.
            return;
        }
        upsert(keycloakId, TokenClaims.username(identity), Roles.effective(identity.getRoles()));
    }

    /**
     * Yerel kaydı istenen hale getirir. Çağıran bir transaction içinde olmalı.
     */
    @Transactional
    public void upsert(String keycloakId, String username, String roleName) {
        Role role = UserService.localRole(roleName);
        AppUser user = AppUser.byKeycloakId(keycloakId);

        if (user == null) {
            user = new AppUser();
            user.keycloakId = keycloakId;
            user.username = username;
            user.role = role;
            user.persist();
            LOG.infof("Yerel kullanıcı kaydı oluşturuldu: %s (%s)", username, roleName);
            return;
        }

        boolean changed = false;
        if (!user.username.equals(username)) {
            user.username = username;
            changed = true;
        }
        if (!user.role.name.equals(roleName)) {
            user.role = role;
            changed = true;
        }
        if (changed) {
            LOG.infof("Yerel kullanıcı kaydı güncellendi: %s (%s)", username, roleName);
        }
    }

    /** Sadece test/okunabilirlik için: kimliğin uygulama rolü. */
    static String effectiveRoleOf(Set<String> roles) {
        return Roles.effective(roles);
    }
}
