package org.example.user;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Kimlik doğrulanmış her istekte kullanıcının yerel kaydını güncel tutar.
 *
 * <p>Bkz. {@link UserProvisioningService} — asıl gerekçe orada. Filtre,
 * kaydın kaynak (Keycloak konsolu / uygulama) fark etmeksizin var olmasını
 * garanti eden tek nokta olduğu için buraya kondu; her uç kendi başına
 * kontrol etseydi biri unutulduğunda sessizce bozulurdu.
 *
 * <p>Maliyet: istek başına {@code keycloak_id} unique index'i üzerinden bir
 * SELECT. Yazma yalnızca kayıt eksikse veya ad/rol değişmişse yapılır.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 100)
public class UserProvisioningFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(UserProvisioningFilter.class);

    @Inject
    SecurityIdentity identity;

    @Inject
    UserProvisioningService provisioning;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (identity == null || identity.isAnonymous()) {
            return;
        }
        try {
            provisioning.syncFromIdentity(identity);
        } catch (RuntimeException e) {
            // Eşitleme isteği düşürmemeli: kullanıcı Keycloak'ta doğrulanmış durumda,
            // yerel aynanın gecikmesi okuma uçlarını engellememeli. Yazma uçları
            // kaydı zaten kendileri oluşturuyor.
            LOG.warnf(e, "Yerel kullanıcı kaydı eşitlenemedi: %s",
                identity.getPrincipal().getName());
        }
    }
}
