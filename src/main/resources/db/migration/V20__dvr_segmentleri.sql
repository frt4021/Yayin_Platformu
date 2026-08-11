-- DVR kaydı MediaMTX'ten alınıp nesne depolamaya taşınıyor.
--
-- ESKİ DÜZEN
--   MediaMTX kendi kaydını /recordings altına yazıyordu ve geriye sarma,
--   MediaMTX'in playback sunucusundan (:9996) okunuyordu. Zaman çizelgesi
--   veritabanında DEĞİL, her istekte MediaMTX'e sorularak üretiliyordu.
--
-- NEDEN DEĞİŞTİ
--   Kayıtların diskte durması, başka bir makineden erişimi ve saklama
--   yönetimini yerel dosya sistemine bağlıyordu. MinIO'ya taşınınca ikisi de
--   çözülüyor -- ama MediaMTX'in S3 desteği yok (ikilide S3 izi bile çıkmadı)
--   ve playback sunucusu yalnızca kendi yerel dizinini okuyor. Dolayısıyla
--   hem yazma hem okuma bizim tarafa geçti ve zaman çizelgesinin bir yerde
--   tutulması gerekti: bu tablo o çizelge.
--
-- NEDEN AYRI TABLO (kliplerle birleştirilmedi)
--   Klip kullanıcının istediği, kalıcı ve adı olan bir çıktı. Segment ise
--   sistemin sürekli ürettiği, sahibi olmayan ve 7 günde silinen bir ham
--   parça. Tek tabloda toplansalardı klip sorgularının her biri milyonlarca
--   segment satırını süzmek zorunda kalırdı.
create table dvr_segments
(
    id            uuid        primary key default gen_random_uuid(),

    -- Kanal silinince segmentleri de gitmeli: kaynağı olmayan bir kayıt
    -- parçasının hiçbir anlamı yok ve zaman çizelgesine asla düşmez.
    channel_id    uuid        not null references channels (id) on delete cascade,

    -- Segmentin kapsadığı yayın aralığı. UTC saklanır.
    --
    -- Değerler DUVAR SAATİNDEN geliyor, medya içindeki zaman damgalarından
    -- değil: ffmpeg RTSP'yi gerçek zamanlı okuduğu için sapma boru tamponu
    -- kadar kalıyor (3 Mbps'te saniyenin altı). Kesin hizalama TS akışındaki
    -- PCR alanının ayrıştırılmasını gerektirir; geriye sarmada saniye altı
    -- doğruluk aranmadığı için bu aşamada yapılmadı.
    basladi       timestamptz not null,
    bitti         timestamptz not null,

    -- MinIO anahtarı: <kanal>/<YYYY>/<AA>/<GG>/<SS>/<zaman>.ts
    --
    -- Kullanıcı öneki YOK -- klip, ekran görüntüsü ve videonun aksine DVR'ın
    -- sahibi yok; kanalın sürekli sistem kaydı, kimse "üretmiyor".
    --
    -- Tekil: aynı segmentin iki kez yazılması, zaman çizelgesinde çakışan
    -- aralık demek olurdu.
    nesne_anahtari varchar(512) not null unique,

    boyut_bayt    bigint      not null,

    created_at    timestamptz not null default now()
);

-- Geriye sarmanın TEK sorgu deseni: "şu kanalda şu aralığa değen segmentler".
-- Kanal ve başlangıç birlikte indeksleniyor; yalnızca kanal indekslenseydi
-- 7 günlük veride tek kanal için ~20 bin satır taranırdı.
create index idx_dvr_segments_kanal_zaman on dvr_segments (channel_id, basladi);

comment on table dvr_segments is
    'DVR kayıt parçalarının zaman çizelgesi. Nesnelerin kendisi MinIO''da; '
        'bu tablo hangi anın hangi nesnede olduğunu söyler.';
