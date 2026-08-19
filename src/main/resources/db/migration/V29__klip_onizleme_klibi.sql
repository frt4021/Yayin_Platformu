-- Klip ızgarasında fare kartın üzerine geldiğinde oynayan kısa, sessiz
-- önizleme klibinin nesne anahtarı. null = henüz üretilmedi ya da üretim
-- başarısız oldu (VideoWorker'daki önizleme toleransıyla aynı ilke: klip
-- yine de HAZIR olur, kart yalnızca ikon yer tutucuya düşer).
alter table clips add column preview_key varchar(512);
