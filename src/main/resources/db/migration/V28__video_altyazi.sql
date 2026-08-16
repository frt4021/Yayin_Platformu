-- Yuklenen videolar icin altyazi (STT) is durumu -- klip/video kuyruk
-- desenindeki AYNI "tablo = kuyruk, SKIP LOCKED = talep" ilkesi, ayri bir
-- kuyruk/worker (video isleme kuyrugunu STT ile paylasip yavaslatmamak icin).
alter table videos add column subtitle_status varchar(16) not null default 'KAPALI';
alter table videos add column subtitle_langs varchar;
alter table videos add column subtitle_error varchar;

create index video_altyazi_durum on videos (subtitle_status);
