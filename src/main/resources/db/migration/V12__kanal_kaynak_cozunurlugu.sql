-- Kaynağın gerçek çözünürlüğü ve MediaMTX'e verilecek çözümlenmiş adres.
--
-- İKİ SORUNU BİRDEN ÇÖZÜYOR:
--
-- 1) MediaMTX master playlist'ten EN YUKSEK bant genisligini seciyor ve o
--    varyantin segmentleri gohlslib'in ~4 MB'lik sinirini asiyorsa yayin
--    hic baslamiyor:
--        ERR [path kanal2] [HLS source] max recorded size exceeded
--    Olculen: TRT 720p 3.01 MB calisiyor, 1080p 4.29 MB dusuyor. Sinir
--    yapilandirilabilir DEGIL (hlsSegmentMaxSize yalnizca HLS SUNUCUSUNU
--    etkiliyor, kaynak okuyucusunu degil -- denenerek dogrulandi).
--
-- 2) Cozunurluk merdiveni kaynagin uzerine cikabiliyordu. Kaynagin gercek
--    boyutu bilinmedigi icin dogrulanamiyordu; artik biliniyor.
--
-- resolved_source_url, kullanicinin girdigi adresi EZMIYOR: girdi
-- source_url'de kalir, MediaMTX'e yazilan adres burada durur. Boylece master
-- playlist'e yeni varyantlar eklendiginde yeniden secim yapilabilir ve
-- kullanici ne yazdigini gormeye devam eder.

alter table channels
    add column resolved_source_url varchar(512),
    add column source_width        int,
    add column source_height       int;

comment on column channels.resolved_source_url is
    'MediaMTX''e yazilan adres. Master playlist''ten secilen varyant; NULL ise source_url kullanilir.';
comment on column channels.source_width is
    'Kaynagin gercek genisligi. NULL = tespit edilemedi (HLS olmayan kaynak veya erisilemedi).';
comment on column channels.source_height is
    'Kaynagin gercek yuksekligi. Merdiven dogrulamasi buna bakar.';
