package org.example.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.example.auth.dto.TokenResponse;
import org.example.etkinlik.EtkinlikService;
import org.example.etkinlik.EtkinlikTuru;
import org.example.exception.AppException;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Giriş, token yenileme ve çıkış. Kimlik bilgisi doğrulaması tamamen
 * Keycloak'ta yapılır — uygulama şifre görmez, saklamaz, karşılaştırmaz;
 * sadece Keycloak'a sorup sonucu iletir.
 *
 * <p>Hata mesajlarında kullanıcı adının var olup olmadığı, hesabın kapalı mı
 * yoksa şifrenin mi yanlış olduğu ayrımı <b>dışarıya verilmez</b>. Aksi halde
 * bu uç bir kullanıcı adı numaralandırma aracına dönüşürdü.
 */
@ApplicationScoped
public class AuthService {

    private static final Logger LOG = Logger.getLogger(AuthService.class);

    private static final String GRANT_PASSWORD = "password";
    private static final String GRANT_REFRESH = "refresh_token";

    /**
     * Yönetici geçici şifre ile kullanıcı açtığında Keycloak, kullanıcı ilk
     * girişini yapıp şifresini değiştirene kadar direct grant'i bu gerekçeyle
     * reddeder. Genel "hatalı giriş" mesajı burada yanıltıcı olurdu.
     */
    private static final String NOT_FULLY_SET_UP = "Account is not fully set up";

    @Inject
    @RestClient
    KeycloakTokenClient tokenClient;

    @Inject
    EtkinlikService etkinlikService;

    @Inject
    ObjectMapper json;

    @ConfigProperty(name = "keycloak.realm")
    String realm;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    String clientSecret;

    public TokenResponse login(String username, String password) {
        try {
            TokenResponse token =
                tokenClient.passwordGrant(realm, GRANT_PASSWORD, clientId, clientSecret, username, password);
            LOG.infof("Giriş başarılı: %s", username);
            etkinlikService.kaydetGirisDenemesi(EtkinlikTuru.GIRIS, username);
            return token;
        } catch (WebApplicationException e) {
            LOG.infof("Giriş reddedildi: %s (HTTP %d)", username, e.getResponse().getStatus());
            etkinlikService.kaydetGirisDenemesi(EtkinlikTuru.GIRIS_BASARISIZ, username);
            throw loginFailure(e);
        }
    }

    public TokenResponse refresh(String refreshToken) {
        try {
            return tokenClient.refreshGrant(realm, GRANT_REFRESH, clientId, clientSecret, refreshToken);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 400 || status == 401) {
                throw AppException.unauthorized(
                    "Oturum süresi dolmuş veya geçersiz. Lütfen yeniden giriş yapın.");
            }
            throw AppException.upstreamError("Keycloak token yenileyemedi (HTTP " + status + ").", e);
        }
    }

    public void logout(String refreshToken) {
        // Bu uc @PermitAll ve JWT olmadan cagriliyor (frontend anonymous:true ile
        // gonderiyor) -- kullaniciyi ancak refresh token'in kendi "sub" claim'inden
        // (DOGRULANMADAN, salt bilgi amacli) cozebiliriz. Guvenlik karari buna
        // dayanmiyor; sadece denetim izinde "kim cikis yapti" alani icin.
        etkinlikService.kaydet(EtkinlikTuru.CIKIS, subFromToken(refreshToken), null, null, Map.of());
        try {
            tokenClient.logout(realm, clientId, clientSecret, refreshToken);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 400 || status == 401) {
                // Token zaten geçersiz/süresi dolmuş — istenen sonuç (oturum kapalı)
                // hâlihazırda sağlanmış durumda, çağıranı hata ile uğraştırmıyoruz.
                LOG.debugf("Çıkışta token zaten geçersizdi (HTTP %d).", status);
                return;
            }
            throw AppException.upstreamError("Keycloak oturumu kapatamadı (HTTP " + status + ").", e);
        }
    }

    /**
     * Bir JWT'nin payload'ındaki {@code sub} claim'ini imza DOĞRULAMADAN okur.
     * Yalnızca {@link #logout} içindeki denetim izi için kullanılır — hiçbir
     * yetki kararı buna dayanmaz. Ayrıştırma başarısız olursa (beklenmedik
     * biçim) çıkışın kendisini engellemeden {@code null} döner.
     */
    private String subFromToken(String jwt) {
        try {
            String[] parcalar = jwt.split("\\.");
            byte[] payload = Base64.getUrlDecoder().decode(parcalar[1]);
            JsonNode node = json.readTree(new String(payload, StandardCharsets.UTF_8));
            return node.path("sub").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verilen şifrenin kullanıcının mevcut şifresi olup olmadığını sınar.
     *
     * <p>Admin REST API'de "bu şifre doğru mu" diye soran bir uç yok, sadece
     * şifreyi ezen reset var. Mevcut şifreyi sormadan değiştirmek, ele
     * geçirilmiş bir access token'ın hesabı kalıcı olarak devralmasına izin
     * verirdi; bu yüzden doğrulamayı bir giriş denemesiyle yapıyoruz.
     */
    public void verifyPassword(String username, String password) {
        try {
            tokenClient.passwordGrant(realm, GRANT_PASSWORD, clientId, clientSecret, username, password);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 400 || status == 401) {
                throw AppException.badRequest("Mevcut şifre hatalı.");
            }
            throw AppException.upstreamError(
                "Keycloak şifre doğrulaması yapılamadı (HTTP " + status + "). "
                    + "Client'ta 'Direct access grants' açık mı?", e);
        }
    }

    private AppException loginFailure(WebApplicationException e) {
        Response response = e.getResponse();
        int status = response.getStatus();

        if (status == 400 || status == 401) {
            if (errorBody(response).contains(NOT_FULLY_SET_UP)) {
                return AppException.unauthorized(
                    "Hesabınız henüz tamamlanmamış. Geçici şifrenizi Keycloak hesap "
                        + "sayfasından değiştirdikten sonra giriş yapabilirsiniz.");
            }
            return AppException.unauthorized("Kullanıcı adı veya şifre hatalı.");
        }
        return AppException.upstreamError("Keycloak giriş isteğini işleyemedi (HTTP " + status + ").", e);
    }

    private String errorBody(Response response) {
        try {
            String body = response.readEntity(String.class);
            return body == null ? "" : body;
        } catch (RuntimeException ignored) {
            // Gövde okunamadıysa ayrım yapamayız; genel mesaja düşülür.
            return "";
        }
    }
}
