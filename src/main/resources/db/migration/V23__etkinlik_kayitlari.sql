-- Kullanici davranisi denetim izi: giris/cikis, izleme/dinleme oturumlari,
-- admin/icerik eylemleri, altyazi dil tercihi. Tek, genel amacli tablo --
-- "tur" duz metin, yeni bir olay tipi eklemek migration gerektirmesin diye.
create table etkinlik_kayitlari (
    id                  uuid primary key default gen_random_uuid(),

    -- Kullanici silinse (yerelde veya Keycloak'ta) bile iz kaybolmamali,
    -- bu yuzden ON DELETE SET NULL + ham adin ayrica saklanmasi
    -- (V21'deki channel_name deseniyle ayni gerekce).
    kullanici_id        uuid references users(id) on delete set null,
    kullanici_adi       varchar(64),

    tur                 varchar(64) not null,
    hedef_turu          varchar(32),
    hedef_id            uuid,

    detay               jsonb not null default '{}',
    olusturma_zamani    timestamptz not null default now()
);

create index etkinlik_zaman on etkinlik_kayitlari (olusturma_zamani desc);
create index etkinlik_kullanici_zaman on etkinlik_kayitlari (kullanici_id, olusturma_zamani desc);
create index etkinlik_tur_zaman on etkinlik_kayitlari (tur, olusturma_zamani desc);
