-- Izlenme sayisi. YouTube tarzi kütüphane görünümünde gösteriliyor;
-- "Oynat" düğmesine basildiginda (/links uç noktasi) artiyor.
alter table videos add column view_count bigint not null default 0;