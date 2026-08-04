-- DVR kaydının hangi çözünürlükten alınacağı.
--
-- Şimdiye kadar kayıt her zaman KAYNAK path'inden alınıyordu. Ölçümde kaynak
-- 2.33 Mbps, 720p rendition 1.65 Mbps çıktı — 720p'den kaydetmek diskte
-- %29 tasarruf demek. 7 gün × 16 kanalda bu 7.26 TB yerine 5.15 TB.
--
-- Boş string = kaynaktan kaydet (rendition yoksa tek seçenek bu).
-- Dolu ise renditions listesindeki bir ad olmalı ('720p' gibi).
alter table channels
    add column dvr_rendition varchar(32) not null default '';

comment on column channels.dvr_rendition is
    'DVR kaydinin alinacagi rendition adi. Bos = kaynak cozunurlugu.';

-- Mevcut DVR açık kanallarda 720p varsa oraya çek: yeni varsayılan bu.
update channels
   set dvr_rendition = '720p'
 where dvr_enabled
   and renditions like '%720p|%';
