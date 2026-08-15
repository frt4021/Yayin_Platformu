-- Video isi haritasi ve oturum sayisi sorgulari icin: tur = 'VIDEO_IZLEME_BITTI'
-- and hedef_id = :id. V23'teki indeksler (olusturma_zamani, kullanici_id,
-- tur+olusturma_zamani) bu (tur, hedef_id) kombinasyonunu kapsamiyor.
create index etkinlik_tur_hedef on etkinlik_kayitlari (tur, hedef_id);
