-- Video kütüphanesi.
--
-- Tablo aynı zamanda KUYRUKTUR — kliplerdeki (V5) desenin aynısı. Ayrı bir
-- mesaj kuyruğu yerine veritabanı kullanılıyor çünkü iş zaten burada kalıcı
-- olmak zorunda: iki yere birden yazmak, biri başarılı diğeri başarısız
-- olduğunda ya kaybolan ya iki kez işlenen işler üretirdi.
--
-- AKIS:
--   1. Kayit YUKLENIYOR olarak acilir, istemciye imzali PUT adresi verilir.
--   2. Tarayici dosyayi DOGRUDAN MinIO'ya yazar (backend'den gecmez).
--   3. Istemci "tamamlandi" der; backend nesneyi statObject ile dogrulayip
--      ISLENIYOR'a alir ve kuyruga birakir.
--   4. Isci ffprobe ile metadata cikarir, thumbnail uretir, HAZIR yapar.

create table videos
(
    id                   uuid         primary key default gen_random_uuid(),

    title                varchar(200) not null,
    description          text,

    -- Nesne anahtari SUNUCU tarafinda uretilir, istemciden ALINMAZ.
    -- Istemci belirleseydi baska bir videonun ya da klibin anahtarini
    -- gonderip uzerine yazabilirdi; imzali PUT adresi tam olarak o anahtara
    -- yazma yetkisi veriyor.
    object_key           varchar(512) not null unique,

    -- Kucuk resim anahtari. Isci tarafindan uretilen kare de, kullanicinin
    -- yukledigi gorsel de burayi doldurur -- ikisi arasindaki fark
    -- thumbnail_at_seconds'in dolu olup olmamasindan anlasilir.
    thumbnail_key        varchar(512),

    -- Elle secilen kare ani (saniye). NULL = otomatik secim, ya da kullanici
    -- kendi gorselini yuklemis.
    thumbnail_at_seconds int,

    -- Indirme adinda kullanilir; object_key'e guvenilmez cunku o uuid.
    original_filename    varchar(255),
    content_type         varchar(100),

    -- Asagidaki dort alan ISCI tarafindan doldurulur. Istemcinin bildirdigi
    -- degerlere guvenilmiyor: imzali adrese herhangi bir bayt dizisi
    -- yazilabilir, dolayisiyla gercek boyut ve sure ancak dosya okunarak
    -- bilinir.
    size_bytes           bigint,
    duration_seconds     int,
    width                int,
    height               int,

    -- YUKLENIYOR | ISLENIYOR | HAZIR | HATA
    status               varchar(16)  not null default 'YUKLENIYOR',
    error                text,

    -- Kac kez denendi. Gecici hatalarda yeniden denenir, kalicilarda denenmez.
    attempts             int          not null default 0,

    uploaded_by          uuid         not null references users (id),

    created_at           timestamptz  not null default now(),
    updated_at           timestamptz  not null default now(),
    completed_at         timestamptz,

    constraint videos_durum_gecerli
        check (status in ('YUKLENIYOR', 'ISLENIYOR', 'HAZIR', 'HATA')),
    -- HAZIR bir videonun kucuk resmi ve boyutu olmak zorunda; bu kisit,
    -- yarim islenmis bir kaydin arayuze "hazir" diye dusmesini engelliyor.
    constraint videos_hazir_eksiksiz
        check (status <> 'HAZIR' or (thumbnail_key is not null and size_bytes is not null))
);

comment on column videos.object_key is
    'MinIO nesne anahtari. Sunucu uretir; istemciden asla alinmaz.';
comment on column videos.status is
    'YUKLENIYOR | ISLENIYOR | HAZIR | HATA';
comment on column videos.thumbnail_at_seconds is
    'Elle secilen kare ani. NULL = otomatik secim veya kullanici gorseli.';

-- "Benim yukledigim videolar" ve genel liste (yeniden eskiye).
create index idx_videos_yukleyen on videos (uploaded_by, created_at desc);

-- Isci bekleyen isleri buradan ceker. Kismi index: tamamlanmis kayitlar
-- taranmaz, tablo buyudukce kuyruk sorgusu yavaslamaz.
create index idx_videos_isleniyor on videos (created_at)
    where status = 'ISLENIYOR';

-- Supurucu, yarim kalmis yuklemeleri bulur. Tarayici "tamamlandi" demeden
-- kapanirsa kayit sonsuza kadar YUKLENIYOR'da kalirdi.
create index idx_videos_yarim on videos (created_at)
    where status = 'YUKLENIYOR';

-- Baslik aramasi buyuk/kucuk harf duyarsiz yapiliyor.
create index idx_videos_baslik on videos (lower(title));
