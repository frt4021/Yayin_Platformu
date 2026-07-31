-- Rol isimleri Türkçeleştirildi.
--
-- Aksanlı harf kullanılmıyor (YÖNETİCİ değil YONETICI): bu isimler Keycloak
-- realm rolü olarak da geçiyor ve JWT claim'i, URL yolu, @RolesAllowed sabiti
-- olarak dolaşıyor. ASCII dışı karakterler bu üç yerde de kodlama sorunu
-- çıkarabildiği için isimler ASCII tutuldu.
--
-- users.role_id foreign key olduğu için isim güncellemesi mevcut kullanıcı
-- eşleşmelerini bozmaz; id'ler değişmiyor.

update roles set name = 'YONETICI' where name = 'ADMIN';
update roles set name = 'IZLEYICI' where name = 'VIEWER';
-- MODERATOR her iki dilde de aynı yazılıyor, dokunulmadı.
