-- Test verisi: 20 kanal + 10 radyo.
--
-- Adreslerin hepsi 12 Ağustos 2026'da denenerek doğrulandı; ayrıntı ve
-- yeniden doğrulama yöntemi için docs/test-yayinlari.md.
--
-- ÇALIŞTIRMA
--   ./yapilandir.sh --test-verisi
-- ya da elle:
--   docker exec -i postgres psql -U app_user -d yayin_merkezi < src/main/resources/test-verisi.sql
--
-- TEKRAR ÇALIŞTIRILABİLİR. name ve mediamtx_path tekil; ON CONFLICT ile
-- var olanlar atlanıyor, hiçbir şey ezilmiyor.
--
-- DİKKAT: BU DOSYA MEDIAMTX'E HABER VERMEZ.
--   Kanal tanımı yalnızca veritabanına yazılıyor. MediaMTX path'leri bellekte
--   tutuyor ve buradan haberi olmuyor. Yayınların başlaması için:
--     · backend yeniden başlatılmalı (ChannelRestorer açılışta yazıyor), ya da
--     · Kanallar sayfasındaki "MediaMTX'e yeniden yaz" düğmesine basılmalı.
--   Aksi halde kanallar listede görünür ama hiçbiri akmaz.

DO $$
DECLARE
    sahip uuid;
BEGIN
    -- created_by ZORUNLU ve users tablosuna bağlı. Kullanıcılar Keycloak'tan
    -- ilk girişte eşitleniyor; hiç giriş yapılmamışsa tablo boş olur ve
    -- insert'ler anlaşılmaz bir FK hatasıyla düşerdi.
    SELECT id INTO sahip FROM users ORDER BY created_at LIMIT 1;

    IF sahip IS NULL THEN
        RAISE EXCEPTION
            'users tablosu boş. Önce arayüzden bir kez giriş yapın (admin1 / 12345678); '
            'kullanıcı Keycloak''tan o anda eşitleniyor.';
    END IF;

    -- ---------------------------------------------------------------- kanallar
    --
    -- dvr_enabled yalnızca ilk beşte açık. Yirmisinde birden açmak 7 günde
    -- ~4,5 TB eder (20 × 7 gün × 3 Mbps) ve test kurulumunda MinIO'yu
    -- doldurur. Gerekirse arayüzden tek tek açılabiliyor.
    --
    -- renditions BOŞ: transkod kapalı, kaynak ne veriyorsa o dağıtılıyor.
    -- 20 kanalda rendition üretmek VAAPI'de bile ~2,8 çekirdek ister.
    INSERT INTO channels (name, source_url, mediamtx_path, active, dvr_enabled, renditions, created_by)
    VALUES
        ('TRT Haber',        'https://tv-trthaber.medya.trt.com.tr/master.m3u8',    'trt-haber',      true, true,  '', sahip),
        ('TRT 1',            'https://tv-trt1.medya.trt.com.tr/master.m3u8',        'trt-1',          true, true,  '', sahip),
        ('TRT Spor',         'https://tv-trtspor1.medya.trt.com.tr/master.m3u8',    'trt-spor',       true, true,  '', sahip),
        ('TRT Belgesel',     'https://tv-trtbelgesel.medya.trt.com.tr/master.m3u8', 'trt-belgesel',   true, true,  '', sahip),
        ('TRT World',        'https://tv-trtworld.medya.trt.com.tr/master.m3u8',    'trt-world',      true, true,  '', sahip),

        ('TRT Spor Yıldız',  'https://tv-trtspor2.medya.trt.com.tr/master.m3u8',    'trt-spor-yildiz', true, false, '', sahip),
        ('TRT Çocuk',        'https://tv-trtcocuk.medya.trt.com.tr/master.m3u8',    'trt-cocuk',      true, false, '', sahip),
        ('TRT Müzik',        'https://tv-trtmuzik.medya.trt.com.tr/master.m3u8',    'trt-muzik',      true, false, '', sahip),
        ('TRT Avaz',         'https://tv-trtavaz.medya.trt.com.tr/master.m3u8',     'trt-avaz',       true, false, '', sahip),
        ('TRT Kurdî',        'https://tv-trtkurdi.medya.trt.com.tr/master.m3u8',    'trt-kurdi',      true, false, '', sahip),
        ('TRT Arabi',        'https://tv-trtarabi.medya.trt.com.tr/master.m3u8',    'trt-arabi',      true, false, '', sahip),
        ('TRT Türk',         'https://tv-trtturk.medya.trt.com.tr/master.m3u8',     'trt-turk',       true, false, '', sahip),
        ('Red Bull TV',      'https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8', 'redbull', true, false, '', sahip),

        ('DW English',       'https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8', 'dw-en', true, false, '', sahip),
        ('DW Arabia',        'https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/index.m3u8', 'dw-ar', true, false, '', sahip),
        ('DW Español',       'https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8', 'dw-es', true, false, '', sahip),
        ('Al Jazeera English','https://live-hls-web-aje.getaj.net/AJE/index.m3u8',  'aljazeera-en',   true, false, '', sahip),
        ('France 24 English','https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8', 'france24-en', true, false, '', sahip),
        ('NHK World Japan',  'https://nhkwlive-ojp.akamaized.net/hls/live/2003458/nhkwlive-ojp-en/index.m3u8', 'nhk-world', true, false, '', sahip),
        ('NASA TV',          'https://ntv1.akamaized.net/hls/live/2014075/NASA-NTV1-HLS/master.m3u8', 'nasa-tv', true, false, '', sahip)
    ON CONFLICT DO NOTHING;

    -- ---------------------------------------------------------------- radyolar
    --
    -- source_kind = KOPRU: hepsi MP3 yayını ve tarayıcı MP3'ü HLS içinde
    -- oynatamıyor. ffmpeg AAC'ye çeviriyor; ölçülen maliyet köprü başına
    -- ~%2,6 CPU.
    --
    -- bitrate kaynağınkinin ÜSTÜNE çıkarılmıyor: kaliteyi artırmaz, yalnızca
    -- bant genişliği harcar.
    INSERT INTO radios (name, source_url, source_kind, mediamtx_path, bitrate, active, sort_order, created_by)
    VALUES
        ('France Inter',           'https://icecast.radiofrance.fr/franceinter-midfi.mp3',  'KOPRU', 'radyo-france-inter',  '128k', true, 1,  sahip),
        ('France Info',            'https://icecast.radiofrance.fr/franceinfo-midfi.mp3',   'KOPRU', 'radyo-france-info',   '128k', true, 2,  sahip),
        ('FIP',                    'https://icecast.radiofrance.fr/fip-midfi.mp3',          'KOPRU', 'radyo-fip',           '128k', true, 3,  sahip),
        ('France Musique',         'https://icecast.radiofrance.fr/francemusique-midfi.mp3','KOPRU', 'radyo-france-musique','128k', true, 4,  sahip),
        ('Radio Paradise Main',    'https://stream.radioparadise.com/mp3-192',              'KOPRU', 'radyo-rp-main',       '192k', true, 5,  sahip),
        ('Radio Paradise Mellow',  'https://stream.radioparadise.com/mellow-192',           'KOPRU', 'radyo-rp-mellow',     '192k', true, 6,  sahip),
        ('Radio Paradise Rock',    'https://stream.radioparadise.com/rock-192',             'KOPRU', 'radyo-rp-rock',       '192k', true, 7,  sahip),
        ('SomaFM Groove Salad',    'https://ice1.somafm.com/groovesalad-128-mp3',           'KOPRU', 'radyo-soma-groove',   '128k', true, 8,  sahip),
        ('SomaFM Drone Zone',      'https://ice1.somafm.com/dronezone-128-mp3',             'KOPRU', 'radyo-soma-drone',    '128k', true, 9,  sahip),
        ('SomaFM Secret Agent',    'https://ice1.somafm.com/secretagent-128-mp3',           'KOPRU', 'radyo-soma-agent',    '128k', true, 10, sahip)
    ON CONFLICT DO NOTHING;

    RAISE NOTICE 'Test verisi hazır — kanal: %, radyo: %',
        (SELECT count(*) FROM channels), (SELECT count(*) FROM radios);
    RAISE NOTICE 'Yayınların başlaması için backend yeniden başlatılmalı ya da '
        'Kanallar sayfasındaki "MediaMTX''e yeniden yaz" düğmesine basılmalı.';
END $$;
