-- Klibe eslik eden WebVTT altyazi dosyalarinin hangi dillerde uretildigi
-- (virgulle ayrilmis, orn. "tr,en,de"). null = hic uretilmedi (kaynakta
-- altyazi verisi yoktu ya da uretim basarisiz oldu, klip yine de HAZIR).
alter table clips add column subtitle_langs varchar;
