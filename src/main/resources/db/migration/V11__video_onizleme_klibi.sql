-- Kütüphane ızgarasında fare kartın üzerine gelince oynayan kısa klip.
--
-- Neden ayrı bir dosya: önizleme için ASIL videoyu oynatmak, 1080p bir
-- kaynakta birkaç saniye için birkaç megabayt indirmek demekti. Izgarada
-- gezinen bir kullanici onlarca karta ugradiginda bu hizla buyuyor.
-- 5 saniyelik, sessiz, 480 piksel genisliginde bir klip ~200-400 KB.
--
-- NULL olabilir: onizleme bir kolayliktir, uretimi basarisiz olursa video
-- yine izlenebilir kalir ve kart kucuk resme duser.

alter table videos
    add column preview_key varchar(512);

comment on column videos.preview_key is
    'Kisa onizleme klibinin nesne anahtari. NULL = uretilmedi, kart kucuk resme duser.';
