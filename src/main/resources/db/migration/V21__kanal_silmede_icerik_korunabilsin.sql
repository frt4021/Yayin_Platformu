-- Kanal silinirken içeriğin korunabilmesi.
--
-- ÖNCEKİ DURUM
--   V18 ile clips.channel_id CASCADE yapılmıştı; ekran görüntüleri zaten
--   CASCADE'di. Yani kanal silinince klip ve ekran görüntüleri de sessizce
--   gidiyordu -- kullanıcıya sorulmadan ve MinIO'daki dosyalar ortada
--   kalarak.
--
-- NEDEN DEĞİŞTİ
--   Klip, kullanıcının seçip ürettiği ve emek harcadığı bir çıktı. Kanalın
--   silinmesi, o klibin değerini yok etmiyor: dosya duruyor, aralığı belli,
--   izlenebiliyor. Ekran görüntüsü için de aynısı geçerli.
--
--   Bu yüzden bağ artık koparılabiliyor: kanal gidiyor, içerik kalıyor ve
--   arayüzde "silinmiş kanal" olarak görünüyor.
--
-- NEDEN DVR AYNI MUAMELEYİ GÖRMÜYOR
--   dvr_segments CASCADE olarak KALIYOR. Bir DVR segmenti tek başına hiçbir
--   şey ifade etmiyor: geriye sarmanın anlamı "şu kanalın şu anı" ve kanal
--   yoksa gösterilecek bir yer de yok. Kliple farkı bu -- klip bağımsız bir
--   dosya, segment ise bir kanalın parçası.
--
-- SET NULL, CASCADE DEĞİL
--   Silme anında kullanıcı "içerik de silinsin" derse uygulama satırları
--   ZATEN kendisi siliyor (MinIO nesneleriyle birlikte). Bu kural yalnızca
--   "içerik kalsın" durumunu karşılıyor.

-- --- Klipler ---
ALTER TABLE clips ALTER COLUMN channel_id DROP NOT NULL;

ALTER TABLE clips DROP CONSTRAINT clips_channel_id_fkey;
ALTER TABLE clips
    ADD CONSTRAINT clips_channel_id_fkey
    FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE SET NULL;

-- --- Ekran görüntüleri ---
ALTER TABLE screenshots ALTER COLUMN channel_id DROP NOT NULL;

ALTER TABLE screenshots DROP CONSTRAINT screenshots_channel_id_fkey;
ALTER TABLE screenshots
    ADD CONSTRAINT screenshots_channel_id_fkey
    FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE SET NULL;

-- Kanal adının kopyası.
--
-- Bağ koptuktan sonra "hangi kanaldı" sorusunun cevabı başka hiçbir yerde
-- yok. Arayüzde "silinmiş kanal" demek yerine "TRT Haber (silinmiş)"
-- diyebilmek, aylar sonra klip arşivine bakan biri için tek ipucu.
--
-- Kanal DURURKEN de dolduruluyor: yalnızca silme anında yazılsaydı, silme
-- yolu dışında oluşan her satır boş kalırdı.
ALTER TABLE clips ADD COLUMN channel_name varchar(200);
ALTER TABLE screenshots ADD COLUMN channel_name varchar(200);

-- Mevcut satırlar için kanaldan doldur.
UPDATE clips c SET channel_name = ch.name FROM channels ch WHERE ch.id = c.channel_id;
UPDATE screenshots s SET channel_name = ch.name FROM channels ch WHERE ch.id = s.channel_id;

COMMENT ON COLUMN clips.channel_name IS
    'Kanal adının kopyası. Kanal silinip bağ koptuğunda tek kalan ipucu.';
COMMENT ON COLUMN screenshots.channel_name IS
    'Kanal adının kopyası. Kanal silinip bağ koptuğunda tek kalan ipucu.';
