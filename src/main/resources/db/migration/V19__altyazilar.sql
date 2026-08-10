-- Altyazi parcalari — STT + ceviri ciktisi.
--
-- ZAMAN DAMGALARI MUTLAK, videoya goreli degil. Sebep: izleyici canli
-- yayinda 6-12 saniye geride ve altyazinin dogru kareye oturmasi ancak
-- PROGRAM-DATE-TIME uzerinden esleyerek mumkun. "Simdi uretildi, simdi
-- goster" mantigi altyaziyi 6-12 saniye erken gosterirdi.
--
-- Mutlak damga ayrica su ucunu bedava veriyor:
--   * geriye sarmada ayni sorgu calisiyor,
--   * klip alindiginda altyazi ARALIK SORGUSUYLA geliyor, ayri is gerekmiyor,
--   * ileride video kutuphanesi icin ayni tablo kullanilabilir.
-- Sonradan eklemek pahali olurdu; bastan boyle kuruldu.
CREATE TABLE altyazilar (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id  UUID NOT NULL REFERENCES channels(id) ON DELETE CASCADE,

    baslangic   TIMESTAMPTZ NOT NULL,
    bitis       TIMESTAMPTZ NOT NULL,

    -- Whisper'in tespit ettigi kaynak dil ve guveni. Ceviri icin
    -- kullanilmiyor (pivot Ingilizce) ama saklaniyor: kullaniciya "bu yayin
    -- Rusca" demek, yanlis tespiti elle duzeltmek ve ileride kaynak dilde
    -- altyazi istenirse planlamak icin gerekli.
    kaynak_dil  VARCHAR(8),
    guven       REAL,

    -- Dil kodundan metne: {"en": "...", "tr": "...", "de": "...", "ru": "..."}
    -- Dil basina ayri satir yerine JSONB: bir bolutun tum dilleri BIRLIKTE
    -- uretiliyor ve birlikte okunuyor. Ayri satirlar her sorguda dort kat
    -- birlestirme ve tutarsiz kalma riski getirirdi.
    metinler    JSONB NOT NULL,

    -- Bolut MAX_SEGMENT_MS asildigi icin mi kesildi. Dogruysa cumle
    -- ortasinda bolunmus olabilir; arayuz bunu belirtebilir.
    kesik       BOOLEAN NOT NULL DEFAULT FALSE,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT altyazi_araligi CHECK (bitis > baslangic)
);

-- Ana sorgu: "su kanalda su an araliginda ne soylendi". Oynatici her
-- saniyede bir bunu soruyor.
CREATE INDEX altyazi_kanal_zaman ON altyazilar (channel_id, baslangic);

-- Ayni bolut iki kez yazilmasin: STT yeniden denenirse ya da isci iki kez
-- acilirsa cift kayit olusurdu ve arayuz altyaziyi cift gosterirdi.
CREATE UNIQUE INDEX altyazi_tekil ON altyazilar (channel_id, baslangic);
