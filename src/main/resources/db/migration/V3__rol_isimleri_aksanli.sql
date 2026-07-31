-- Rol isimleri, uygulamada kullanılan aksanlı Türkçe yazımlarına çekildi.
--
-- Bu isimler Keycloak realm rolü olarak da geçerli olmalı: Roles sabitleri,
-- roles.name ve Keycloak realm rol adı üçü birden birebir aynı yazılmalı,
-- aksi halde rol ataması "rol tanımlı değil" hatası verir.
--
-- users.role_id foreign key olduğu için isim güncellemesi mevcut kullanıcı
-- eşleşmelerini bozmaz; id'ler değişmiyor.

update roles set name = 'Yönetici'  where name = 'YONETICI';
update roles set name = 'Moderatör' where name = 'MODERATOR';
update roles set name = 'İzleyici'  where name = 'IZLEYICI';
