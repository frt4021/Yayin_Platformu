-- Izlenme sayisi. Video.view_count (V22) ile ayni desen; "izle/indir" adresi
-- istendiginde (ClipService.links) artiyor.
alter table clips add column view_count bigint not null default 0;
