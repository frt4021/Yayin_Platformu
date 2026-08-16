-- Mevcut index'ler (altyazi_kanal_zaman, altyazi_tekil) (channel_id, baslangic)
-- uzerinde -- kanal filtresi olmadan tum tabloda "baslangic < cutoff"
-- supurmesi (RetentionSweeper) bu index'i verimli kullanamaz (leading
-- column channel_id). Tek kolonlu bir index gerekiyor.
create index altyazi_baslangic on altyazilar (baslangic);
