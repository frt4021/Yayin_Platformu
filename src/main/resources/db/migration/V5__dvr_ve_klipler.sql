-- DVR (geriye sarma) ve klip çıkarma.

-- Kayıt kanal bazında açılır. MediaMTX'e karşılığı path'in record ayarıdır;
-- uygulama bu sütunu değiştirdiğinde MediaMTX'e de yansıtır.
--
-- DİKKAT: 6 Mbps'lik bir kanal 7 günde ~454 GB yazar. Bu sütunu açmadan
-- önce disk kapasitesi hesaplanmalı.
alter table channels add column dvr_enabled boolean not null default false;

-- Klip çıkarma işleri.
--
-- Klipler asenkron üretilir: istek buraya BEKLIYOR olarak yazılır, kuyruğa
-- düşer, arka plandaki işçi MediaMTX'ten çekip nesne depolamasına yazar.
-- Senkron üretilseydi 1 saatlik bir klip (6 Mbps'te ~2.7 GB) HTTP
-- bağlantısını dakikalarca açık tutar, kullanıcı sekmeyi kapatınca iş
-- boşa giderdi.
create table clips
(
    id           uuid        primary key default gen_random_uuid(),
    channel_id   uuid        not null references channels (id),
    requested_by uuid        not null references users (id),

    -- Klibin kapsadığı zaman aralığı. UTC saklanır; gösterimde yerele çevrilir.
    start_at     timestamptz not null,
    end_at       timestamptz not null,

    -- BEKLIYOR | ISLENIYOR | HAZIR | HATA
    status       varchar(16) not null default 'BEKLIYOR',

    -- Nesne depolamasındaki anahtar; yalnızca HAZIR durumunda dolu.
    object_key   varchar(512),
    size_bytes   bigint,

    -- Başarısız işin sebebi; kullanıcıya gösterilir.
    error        text,

    -- Kaç kez denendi. Geçici hatalarda yeniden denenir, kalıcı olanlarda denenmez.
    attempts     int         not null default 0,

    created_at   timestamptz not null default now(),
    started_at   timestamptz,
    completed_at timestamptz,

    constraint clips_aralik_gecerli check (end_at > start_at)
);

comment on column clips.status is 'BEKLIYOR | ISLENIYOR | HAZIR | HATA';

-- Kullanıcının kendi kliplerini listelemesi.
create index idx_clips_requested_by on clips (requested_by, created_at desc);

-- Kanal bazlı listeleme ve kanal silinirken bağlı klipleri bulma.
create index idx_clips_channel on clips (channel_id, created_at desc);

-- İşçinin bekleyen işleri çekmesi. Kısmi index: tamamlanmış işler taranmaz,
-- tablo büyüdükçe kuyruk sorgusu yavaşlamaz.
create index idx_clips_bekleyen on clips (created_at) where status = 'BEKLIYOR';
