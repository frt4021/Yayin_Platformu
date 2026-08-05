-- Küçük resmin kaynağını açıkça işaretle.
--
-- Şimdiye kadar iki durum vardı ve ikisi de thumbnail_at_seconds'tan
-- çıkarılıyordu: NULL ise otomatik, dolu ise kullanıcının seçtiği kare.
-- Kullanıcının KENDI GORSELINI yukleyebilmesi ucuncu bir durum ekliyor ve
-- bu, mevcut iki degerden turetilemez -- yuklenen gorselde de
-- thumbnail_at_seconds bos olurdu, yani "otomatik" ile ayirt edilemezdi.
--
-- Ayrimin onemi: iscinin kucuk resmi yeniden uretmesi, kullanicinin
-- yukledigi gorseli sessizce ezmemeli.

alter table videos
    add column thumbnail_is_upload boolean not null default false;

comment on column videos.thumbnail_is_upload is
    'true = kucuk resim kullanici tarafindan yuklendi; false = videodan uretildi.';
