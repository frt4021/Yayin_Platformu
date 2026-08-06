-- Manuel kayıt: kullanıcı kanalı kayda alıp durdurabilsin.
--
-- YENI BIR KAYIT MEKANIZMASI YOK. DVR zaten sürekli kaydediyor; "kayda başla /
-- durdur" aslında bir zaman aralığı seçimi. Durdurulduğunda mevcut klip işi
-- açılıyor ve kuyruk, yeniden deneme, süpürücü, imzalı indirme hattının
-- tamamı olduğu gibi kullanılıyor.

-- Klibin nasıl üretildiği. Arayüz "kliplerim" ile "kayıtlarım" listelerini
-- ayırabilsin diye; ikisi de aynı tabloda çünkü ürün ve yaşam döngüsü aynı.
alter table clips
    add column origin varchar(16) not null default 'ARALIK';

comment on column clips.origin is
    'ARALIK = zaman cizelgesinden aralik secilerek | MANUEL_KAYIT = kayda basla/durdur ile';

alter table clips
    add constraint clips_origin_gecerli check (origin in ('ARALIK', 'MANUEL_KAYIT'));

-- Kliplerim/kayitlarim listeleri ayri sorgulaniyor.
create index idx_clips_origin on clips (requested_by, origin, created_at desc);

-- Devam eden kayıtlar.
--
-- NEDEN TABLO: sunucu yeniden başlarsa devam eden kayıt kaybolmamalı. Bellekte
-- tutulsaydı kullanıcı "durdur"a bastığında başlangıç anı yok olur ve kayıt
-- hiç üretilemezdi.
--
-- Anahtar (kanal, kullanıcı) çifti: aynı kanalda birden fazla kullanıcı ayrı
-- ayrı kayıt alabilir, ama bir kullanıcı aynı kanalda ikinci bir kayıt
-- başlatamaz.
create table active_recordings
(
    channel_id uuid        not null references channels (id) on delete cascade,
    user_id    uuid        not null references users (id) on delete cascade,
    started_at timestamptz not null default now(),

    primary key (channel_id, user_id)
);

comment on table active_recordings is
    'Devam eden manuel kayitlar. Durdurulunca satir silinir ve clips''e is acilir.';

-- Supurucu, ust siniri asan kayitlari otomatik durduruyor: kullanici sekmeyi
-- kapatirsa kayit sonsuza kadar acik kalirdi.
create index idx_active_recordings_baslangic on active_recordings (started_at);
