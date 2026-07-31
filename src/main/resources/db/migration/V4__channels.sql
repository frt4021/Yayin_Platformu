-- Kanallar.
--
-- MediaMTX'te path'ler REST API (http://mediamtx:9997) üzerinden dinamik
-- oluşturuluyor; bu tablo o path'lerin kalıcı kaydıdır. mediamtx_path,
-- MediaMTX tarafındaki path adıyla birebir aynı olmalı — eşleşme buradan
-- kurulduğu için unique.

create table channels
(
    id            uuid         primary key default gen_random_uuid(),
    name          varchar(128) not null unique,
    source_url    varchar(512) not null,
    mediamtx_path varchar(128) not null unique,
    active        boolean      not null default true,
    -- Birden fazla yönetici olduğunda kanalı kimin eklediğini gösterir.
    -- users.id'ye bağlı (Keycloak id'sine değil): kullanıcı Keycloak'ta
    -- yeniden oluşturulsa bile yerel kayıt ve dolayısıyla bu bağ korunur.
    created_by    uuid         not null references users (id),
    created_at    timestamptz  not null default now()
);

comment on column channels.source_url is 'Kaynak yayin adresi (udp://, rtsp://, srt:// vb.).';
comment on column channels.mediamtx_path is 'MediaMTX''teki path adi; HLS adresi bu addan turer.';

-- "Bu yöneticinin eklediği kanallar" sorgusu için; foreign key tek başına
-- index oluşturmaz.
create index idx_channels_created_by on channels (created_by);

-- Yayın listeleri neredeyse her zaman yalnızca aktif kanalları istiyor.
create index idx_channels_active on channels (active) where active;
