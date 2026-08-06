-- Kanal silinemiyordu: clips.channel_id FK'si NO ACTION idi.
--
--   ERROR: update or delete on table "channels" violates foreign key
--   constraint "clips_channel_id_fkey" on table "clips"
--
-- Kanalda tek bir klip varsa silme reddediliyordu ve kullanici sebebi
-- goremiyordu -- uc yalnizca "Beklenmeyen hata" donuyordu.
--
-- Kanala bagli diger her sey ZATEN cascade ediyordu:
--   active_recordings.channel_id -> CASCADE
--   screenshots.channel_id       -> CASCADE
--   planli_kayitlar.channel_id   -> CASCADE
-- Klipler tek istisnaydi; tutarsizligin bilincli bir gerekcesi yok.
--
-- NOT: satirlar gidiyor ama MinIO'daki dosyalar yerinde kaliyor. Ayni durum
-- ekran goruntulerinde de var (o da cascade). Yetim nesneleri toplayan
-- supurucu ayri bir is olarak duruyor -- bkz. notlar.md.
ALTER TABLE clips DROP CONSTRAINT clips_channel_id_fkey;

ALTER TABLE clips
    ADD CONSTRAINT clips_channel_id_fkey
    FOREIGN KEY (channel_id) REFERENCES channels(id) ON DELETE CASCADE;
