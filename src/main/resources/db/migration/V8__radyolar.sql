-- Radyo yayınları.
--
-- Kanallarla aynı altyapıdan (MediaMTX) dağıtılıyor ama AYRI tabloda:
-- renditions, dvr_enabled ve dvr_rendition sütunlarının radyoda hiçbir
-- karşılığı yok. channels'a bir "tur" sütunu eklemek, her sorguya "bu satır
-- radyo mu" kontrolü taşıtır ve anlamsız sütunları kalıcı hale getirirdi.

create table radios
(
    id            uuid         primary key default gen_random_uuid(),
    name          varchar(128) not null unique,
    source_url    varchar(512) not null,

    -- DOGRUDAN: adres MediaMTX'in source alanina yazilir (HLS/RTSP/RTMP/SRT/UDP).
    -- KOPRU:    source=publisher + runOnInit ile ffmpeg adresi cekip RTSP'e basar.
    --
    -- NEDEN ACIKCA SECILIYOR: MediaMTX http(s) kaynaklarini HLS sayiyor.
    -- Duz bir Icecast MP3 adresi (http://yayin.ornek.com:8000/canli) path
    -- yazilirken KABUL EDILIYOR ama calisma zamaninda m3u8 bekleyip bos
    -- donuyor -- kullanici hata gormuyor, yayin hicbir zaman baslamiyor.
    -- Adresten tahmin etmek bu sessiz basarisizligi uretirdi: Icecast
    -- adreslerinin cogunda uzanti yok, HLS adreslerinin hepsi .m3u8 ile
    -- bitmiyor.
    source_kind   varchar(16)  not null,

    mediamtx_path varchar(128) not null unique,

    -- Yalnizca KOPRU icin: ffmpeg'in uretecegi AAC bit hizi.
    -- Kanal bazinda, cunku rendition dersiyle ayni -- 64k'lik bir yayini
    -- 128k'ya kodlamak kaliteyi artirmaz, yalnizca bant genisligi harcar.
    bitrate       varchar(16)  not null default '128k',

    active        boolean      not null default true,
    logo_url      varchar(512),
    sort_order    int          not null default 0,

    created_by    uuid         not null references users (id),
    created_at    timestamptz  not null default now(),

    constraint radios_kaynak_turu_gecerli check (source_kind in ('DOGRUDAN', 'KOPRU'))
);

comment on column radios.source_kind is
    'DOGRUDAN = MediaMTX source alanina yazilir | KOPRU = ffmpeg ile cekilip publish edilir.';
comment on column radios.bitrate is
    'KOPRU modunda uretilecek AAC bit hizi. DOGRUDAN modda kullanilmaz.';
comment on column radios.mediamtx_path is
    'MediaMTX''teki path adi; HLS adresi bu addan turer.';

-- Foreign key tek basina index olusturmaz.
create index idx_radios_created_by on radios (created_by);

-- Dinleme listeleri yalnizca aktif radyolari, siralamasiyla istiyor.
create index idx_radios_aktif on radios (sort_order, name) where active;
