-- Klip ızgarasındaki kart için gerçek bir kapak karesi (önizleme klibinden
-- çıkarılan tek kare JPEG). null = henüz üretilmedi ya da üretim başarısız
-- oldu (aynı tolerans: klip yine de HAZIR olur, kart ikon yer tutucuya düşer).
alter table clips add column thumbnail_key varchar(512);
