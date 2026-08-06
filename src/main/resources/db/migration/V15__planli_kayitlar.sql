-- Planli kayit: kullanicinin ONCEDEN verdigi kayit emri.
--
-- Manuel kayittan (active_recordings) farki, bitis aninin BASTAN belli olmasi.
-- Kullanici "durdur"a basmiyor; aralik gecince is kendiliginden kliplesiyor.
-- Bu yuzden ayri tablo: active_recordings'in bitis sutunu yok ve olmasi da
-- anlamsiz olurdu.
CREATE TABLE planli_kayitlar (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id  UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    baslangic   TIMESTAMPTZ NOT NULL,
    bitis       TIMESTAMPTZ NOT NULL,

    -- BEKLIYOR -> KAYITTA -> TAMAMLANDI | BASARISIZ, ya da her an IPTAL.
    durum       TEXT NOT NULL DEFAULT 'BEKLIYOR',

    -- Uretilen klip. Klip silinirse plan kaydi tarihce olarak kaliyor:
    -- kullanici "kayit emri verdim ama klip nerede" diye sorabilmeli.
    clip_id     UUID REFERENCES clips(id) ON DELETE SET NULL,
    hata        TEXT,

    -- Kanalin DVR'i KAPALIYKEN bu plan icin acildiysa true. Bitiste geri
    -- kapatmak zorundayiz; aksi halde kullanicinin hic istemedigi bir kanal
    -- sonsuza kadar diske yazmaya devam ederdi.
    dvr_bizden  BOOLEAN NOT NULL DEFAULT FALSE,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT planli_kayit_araligi CHECK (bitis > baslangic)
);

-- Zamanlayici her tikta "sirasi gelen" satirlari ariyor; tarama tablo
-- buyudukce pahalilasmasin.
CREATE INDEX planli_kayit_durum_baslangic ON planli_kayitlar (durum, baslangic);
CREATE INDEX planli_kayit_durum_bitis     ON planli_kayitlar (durum, bitis);

-- Kullanicinin kendi planlarini listelemesi en sik sorgu.
CREATE INDEX planli_kayit_kullanici ON planli_kayitlar (user_id, baslangic DESC);
