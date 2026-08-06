-- Canlı yayından yakalanan kareler ve kronolojik galeri.
--
-- Kareyi SUNUCU yakalıyor: kaynak çözünürlüğünde olsun diye. Ama hangi ANI
-- yakalayacağı istemciden geliyor -- HLS'te izlenen an ile canlı uç arasında
-- 6-20 saniye fark var ve sunucudan körlemesine yakalanan kare, kullanıcının
-- gördüğü kare OLMAZDI. İstemci oynatma anını bildiriyor, sunucu o anı
-- DVR'dan çekiyor; DVR kapalıysa canlı uca düşülüyor.

create table screenshots
(
    id          uuid         primary key default gen_random_uuid(),
    channel_id  uuid         not null references channels (id) on delete cascade,
    captured_by uuid         not null references users (id),

    -- Karenin ait oldugu YAYIN ani. created_at ise kaydin olusturuldugu an;
    -- geriye sarmadan yakalananlarda ikisi saatlerce farkli olabiliyor.
    captured_at timestamptz  not null,

    object_key  varchar(512) not null unique,
    width       int,
    height      int,
    size_bytes  bigint       not null,

    -- Kullanicinin kendi notu; galeride arama ve hatirlama icin.
    note        varchar(200),

    created_at  timestamptz  not null default now()
);

comment on column screenshots.captured_at is
    'Karenin ait oldugu yayin ani (created_at degil).';
comment on column screenshots.object_key is
    'MinIO nesne anahtari. Sunucu uretir; istemciden asla alinmaz.';

-- Galeri kronolojik ve kullaniciya ozel: yonetici disinda herkes yalnizca
-- kendi karelerini goruyor.
create index idx_screenshots_kullanici on screenshots (captured_by, captured_at desc);

-- "Bu kanaldan alinan kareler" ve kanal silinirken bagli kayitlari bulma.
create index idx_screenshots_kanal on screenshots (channel_id, captured_at desc);

-- Temizlik supurucusu eski kayitlari yasina gore tariyor.
create index idx_screenshots_yas on screenshots (created_at);
