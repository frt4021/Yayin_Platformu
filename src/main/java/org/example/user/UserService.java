package org.example.user;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.auth.AuthService;
import org.example.etkinlik.EtkinlikService;
import org.example.etkinlik.EtkinlikTuru;
import org.example.exception.AppException;
import org.example.user.dto.ChangePasswordRequest;
import org.example.user.dto.CreateUserRequest;
import org.example.user.dto.KullaniciSayfasiDto;
import org.example.user.dto.ResetPasswordRequest;
import org.example.user.dto.SyncResultDto;
import org.example.user.dto.UserDto;
import org.example.user.entity.AppUser;
import org.example.user.entity.Role;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Kullanıcı yönetiminin tek giriş noktası.
 *
 * <p><b>Doğruluk kaynağı Keycloak'tır.</b> Okuma işlemleri Keycloak'tan yapılır,
 * yazma işlemlerinde önce Keycloak güncellenir, sonra yerel {@code users} tablosu
 * aynalanır. Sıra bilinçli: yerel yazma başarısız olursa işlem geri alınır ama
 * Keycloak'taki değişiklik kalır — bu durumda kayma {@link #sync()} ile ya da
 * kullanıcının bir sonraki isteğinde {@link UserProvisioningService} tarafından
 * kapatılır. Ters sırada çalışsaydık, yerelde var olmayan bir kullanıcıyı
 * göstermek gibi daha kötü bir tutarsızlık doğardı.
 */
@ApplicationScoped
public class UserService {

    private static final Logger LOG = Logger.getLogger(UserService.class);

    /** {@link #sync()} tek seferde en fazla bu kadar kullanıcı çeker. */
    private static final int SYNC_PAGE_LIMIT = 1000;

    @Inject
    Keycloak keycloak;

    @Inject
    AuthService authService;

    @Inject
    UserProvisioningService provisioning;

    @Inject
    EtkinlikService etkinlikService;

    @ConfigProperty(name = "keycloak.realm")
    String realmName;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String clientId;

    /** {@link #clientUuid()} tarafından bir kez çözülür. */
    private volatile String clientUuid;

    // ------------------------------------------------------------------
    // Okuma
    // ------------------------------------------------------------------

    public UserDto get(String keycloakId) {
        UserRepresentation rep = representation(keycloakId);
        return toDto(rep, effectiveRole(keycloakId));
    }

    /**
     * Kullanıcıları listeler. Rol bilgisi Keycloak'ta kullanıcı gösteriminin
     * içinde gelmediği için her kullanıcı başına bir ek istek atılır; bu yüzden
     * uç sayfalıdır ve {@code max} sınırlıdır.
     */
    public KullaniciSayfasiDto list(String search, int first, int max) {
        boolean bos = search == null || search.isBlank();
        List<UserRepresentation> reps = bos
            ? realm().users().list(first, max)
            : realm().users().search(search.trim(), first, max);

        List<UserDto> items = reps.stream()
            .map(rep -> toDto(rep, effectiveRole(rep.getId())))
            .toList();

        // Keycloak'ın AYRI, ucuz bir sayım ucu var (kullanıcı başına rol
        // sorgusu gerektirmiyor) -- toplamı sayfa boyu kadar tahmin etmek
        // yerine gerçek sayıyı burada alıyoruz.
        long toplam = bos ? realm().users().count() : realm().users().count(search.trim());
        return new KullaniciSayfasiDto(items, toplam, first, max);
    }

    // ------------------------------------------------------------------
    // Kullanıcının kendisi
    // ------------------------------------------------------------------

    /**
     * Kullanıcının kendi şifresini değiştirmesi. Mevcut şifre önce
     * doğrulanır — ele geçirilmiş bir access token'ın hesabı kalıcı olarak
     * devralmasını engelleyen tek adım budur.
     */
    public void changeOwnPassword(String keycloakId, String username, ChangePasswordRequest req) {
        if (req.currentPassword().equals(req.newPassword())) {
            throw AppException.badRequest("Yeni şifre mevcut şifre ile aynı olamaz.");
        }
        authService.verifyPassword(username, req.currentPassword());
        setPassword(keycloakId, req.newPassword(), false);
        LOG.infof("Kullanıcı kendi şifresini değiştirdi: %s", username);
    }

    // ------------------------------------------------------------------
    // Yönetici işlemleri
    // ------------------------------------------------------------------

    @Transactional
    public UserDto create(CreateUserRequest req, String actingUserId) {
        String roleName = requireKnownRole(req.role());

        UserRepresentation rep = new UserRepresentation();
        rep.setUsername(req.username());
        rep.setEmail(req.email());
        rep.setFirstName(req.firstName());
        rep.setLastName(req.lastName());
        rep.setEnabled(true);
        rep.setEmailVerified(false);

        String keycloakId;
        try (Response response = realm().users().create(rep)) {
            int status = response.getStatus();
            if (status == Response.Status.CONFLICT.getStatusCode()) {
                throw AppException.conflict(
                    "Bu kullanıcı adı veya e-posta zaten kayıtlı: " + req.username());
            }
            if (status != Response.Status.CREATED.getStatusCode()) {
                throw AppException.upstreamError(
                    "Keycloak kullanıcıyı oluşturamadı (HTTP " + status + ").");
            }
            keycloakId = CreatedResponseUtil.getCreatedId(response);
        }

        setPassword(keycloakId, req.password(), req.temporary());
        replaceClientRole(keycloakId, roleName);
        provisioning.upsert(keycloakId, req.username(), roleName);

        LOG.infof("Kullanıcı oluşturuldu: %s (%s)", req.username(), roleName);
        AppUser hedefKullanici = AppUser.byKeycloakId(keycloakId);
        etkinlikService.kaydet(EtkinlikTuru.KULLANICI_EKLENDI, actingUserId, "kullanici",
            hedefKullanici == null ? null : hedefKullanici.id,
            Map.of("kullaniciAdi", req.username(), "rol", roleName));
        return get(keycloakId);
    }

    /**
     * Rol değiştirir. Bir kullanıcının tek rolü olduğu için mevcut uygulama
     * rolleri kaldırılıp yenisi atanır.
     */
    @Transactional
    public UserDto changeRole(String keycloakId, String roleName, String actingUserId) {
        String newRole = requireKnownRole(roleName);
        UserRepresentation rep = representation(keycloakId);
        String oldRole = effectiveRole(keycloakId);

        if (keycloakId.equals(actingUserId) && !Roles.YONETICI.equals(newRole)) {
            // Aksi halde tek yönetici kendini izleyiciye çevirip paneli kilitleyebilir.
            throw AppException.forbidden("Kendi yönetici rolünüzü kaldıramazsınız.");
        }

        replaceClientRole(keycloakId, newRole);
        provisioning.upsert(keycloakId, rep.getUsername(), newRole);

        LOG.infof("Rol değiştirildi: %s -> %s", rep.getUsername(), newRole);
        AppUser hedefKullanici = AppUser.byKeycloakId(keycloakId);
        etkinlikService.kaydet(EtkinlikTuru.KULLANICI_ROLU_DEGISTI, actingUserId, "kullanici",
            hedefKullanici == null ? null : hedefKullanici.id,
            Map.of("kullaniciAdi", rep.getUsername(), "eskiRol", oldRole, "yeniRol", newRole));
        return get(keycloakId);
    }

    /** Yöneticinin şifre sıfırlaması — mevcut şifre sorulmaz, yetki rolden gelir. */
    public void resetPassword(String keycloakId, ResetPasswordRequest req) {
        UserRepresentation rep = representation(keycloakId);
        setPassword(keycloakId, req.newPassword(), req.temporary());
        LOG.infof("Yönetici şifre sıfırladı: %s (gecici=%s)", rep.getUsername(), req.temporary());
    }

    @Transactional
    public void delete(String keycloakId, String actingUserId) {
        if (keycloakId.equals(actingUserId)) {
            throw AppException.forbidden("Kendi hesabınızı silemezsiniz.");
        }
        UserRepresentation rep = representation(keycloakId);
        AppUser hedefKullanici = AppUser.byKeycloakId(keycloakId);
        UUID hedefId = hedefKullanici == null ? null : hedefKullanici.id;

        try (Response response = realm().users().delete(keycloakId)) {
            int status = response.getStatus();
            if (status >= 400) {
                throw AppException.upstreamError(
                    "Keycloak kullanıcıyı silemedi (HTTP " + status + ").");
            }
        }
        AppUser.deleteByKeycloakId(keycloakId);
        LOG.infof("Kullanıcı silindi: %s", rep.getUsername());
        etkinlikService.kaydet(EtkinlikTuru.KULLANICI_SILINDI, actingUserId, "kullanici", hedefId,
            Map.of("kullaniciAdi", rep.getUsername()));
    }

    // ------------------------------------------------------------------
    // Eşitleme
    // ------------------------------------------------------------------

    /**
     * Keycloak'ı yerel {@code users} tablosuna aynalar.
     *
     * <p>Yönetici kullanıcı işlemlerini uygulama dışından — Keycloak konsolundan —
     * da yapabildiği için iki taraf zamanla ayrışır. Bu uç ayrışmayı kapatır:
     * eksik kullanıcıları ekler, adı/rolü değişenleri günceller, Keycloak'ta
     * artık bulunmayanları <b>raporlar</b> (silmez — bkz. {@link SyncResultDto}).
     */
    @Transactional
    public SyncResultDto sync() {
        List<UserRepresentation> remote = realm().users().list(0, SYNC_PAGE_LIMIT);
        if (remote.size() == SYNC_PAGE_LIMIT) {
            LOG.warnf("Eşitleme %d kullanıcı sınırına takıldı; fazlası bu turda işlenmedi.",
                SYNC_PAGE_LIMIT);
        }

        List<String> created = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        for (UserRepresentation rep : remote) {
            String roleName = effectiveRole(rep.getId());
            AppUser existing = AppUser.byKeycloakId(rep.getId());
            if (existing == null) {
                created.add(rep.getUsername());
            } else if (!existing.username.equals(rep.getUsername())
                || !existing.role.name.equals(roleName)) {
                updated.add(rep.getUsername());
            } else {
                continue;
            }
            provisioning.upsert(rep.getId(), rep.getUsername(), roleName);
        }

        Set<String> remoteIds = remote.stream()
            .map(UserRepresentation::getId)
            .collect(Collectors.toSet());
        List<String> orphaned = AppUser.<AppUser>listAll().stream()
            .filter(local -> !remoteIds.contains(local.keycloakId))
            .map(local -> local.username)
            .toList();

        LOG.infof("Eşitleme tamamlandı: %d eklendi, %d güncellendi, %d artık kayıt.",
            created.size(), updated.size(), orphaned.size());
        return new SyncResultDto(created, updated, orphaned);
    }

    // ------------------------------------------------------------------
    // Keycloak yardımcıları
    // ------------------------------------------------------------------

    private RealmResource realm() {
        return keycloak.realm(realmName);
    }

    private UserRepresentation representation(String keycloakId) {
        try {
            return realm().users().get(keycloakId).toRepresentation();
        } catch (NotFoundException e) {
            throw AppException.notFound("Kullanıcı bulunamadı: " + keycloakId);
        }
    }

    /**
     * {@code Yayın_App} client'ının Keycloak içindeki iç kimliği.
     *
     * <p>Roller realm rolü değil <b>client rolü</b> olarak tanımlı, ve client
     * rolü uçları client'ın clientId'sini değil bu iç UUID'sini istiyor. Değer
     * uygulamanın ömrü boyunca sabit olduğu için bir kez çözülüp saklanıyor.
     */
    private String clientUuid() {
        String cached = clientUuid;
        if (cached != null) {
            return cached;
        }
        List<ClientRepresentation> matches = realm().clients().findByClientId(clientId);
        if (matches.isEmpty()) {
            throw AppException.upstreamError(
                "Keycloak realm'inde '" + clientId + "' client'ı bulunamadı.");
        }
        cached = matches.get(0).getId();
        clientUuid = cached;
        return cached;
    }

    /** Kullanıcıya atanmış client rollerinden uygulamaya ait olanı. */
    private String effectiveRole(String keycloakId) {
        List<String> assigned = clientRoles(keycloakId).listAll().stream()
            .map(RoleRepresentation::getName)
            .toList();
        return Roles.effective(assigned);
    }

    private RoleScopeResource clientRoles(String keycloakId) {
        return realm().users().get(keycloakId).roles().clientLevel(clientUuid());
    }

    /**
     * Uygulamanın tanıdığı rollerden kullanıcıda olanları kaldırır, yerine tek
     * rolü atar. Kullanıcının başka client'lardaki rolleri ve Keycloak'ın kendi
     * realm rolleri (default-roles-*, offline_access) etkilenmez — onlar
     * uygulamanın yetki modelinin parçası değil.
     */
    private void replaceClientRole(String keycloakId, String roleName) {
        RoleScopeResource scope = clientRoles(keycloakId);

        List<RoleRepresentation> current = scope.listAll().stream()
            .filter(r -> Roles.isKnown(r.getName()))
            .toList();

        if (current.size() == 1 && current.get(0).getName().equals(roleName)) {
            return;
        }
        if (!current.isEmpty()) {
            scope.remove(current);
        }
        scope.add(List.of(clientRole(roleName)));
    }

    private RoleRepresentation clientRole(String roleName) {
        try {
            return realm().clients().get(clientUuid()).roles().get(roleName).toRepresentation();
        } catch (NotFoundException e) {
            throw AppException.upstreamError(
                "'" + roleName + "' rolü '" + clientId + "' client'ında tanımlı değil. "
                    + "Client rollerini uygulamadakilerle aynı isimlerle oluşturun: "
                    + String.join(", ", Roles.all()));
        }
    }

    private void setPassword(String keycloakId, String password, boolean temporary) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(password);
        credential.setTemporary(temporary);
        try {
            realm().users().get(keycloakId).resetPassword(credential);
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == 400) {
                // Realm'in şifre politikası (uzunluk, karmaşıklık, geçmiş) reddetti.
                throw AppException.badRequest(
                    "Şifre, Keycloak realm'inin şifre politikasını karşılamıyor.");
            }
            throw AppException.upstreamError("Keycloak şifreyi güncelleyemedi.", e);
        }
    }

    private String requireKnownRole(String roleName) {
        if (!Roles.isKnown(roleName)) {
            throw AppException.badRequest(
                "Geçersiz rol: " + roleName + ". Geçerli roller: " + String.join(", ", Roles.all()));
        }
        return roleName;
    }

    private UserDto toDto(UserRepresentation rep, String roleName) {
        return new UserDto(
            rep.getId(),
            rep.getUsername(),
            rep.getEmail(),
            rep.getFirstName(),
            rep.getLastName(),
            Boolean.TRUE.equals(rep.isEnabled()),
            roleName,
            rep.getCreatedTimestamp() == null ? null : Instant.ofEpochMilli(rep.getCreatedTimestamp())
        );
    }

    /** Rol adından yerel {@link Role} satırını getirir; V1 seed'i garanti eder. */
    static Role localRole(String roleName) {
        Role role = Role.byName(roleName);
        if (role == null) {
            throw AppException.internalError(
                "'" + roleName + "' rolü roles tablosunda yok. Migration eksik olabilir.", null);
        }
        return role;
    }
}
