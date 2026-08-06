-- Manuel kayit artik DVR KAPALI kanallarda da calisiyor.
--
-- Onceden "bu kanalda geriye sarma kapali, kayit alinamaz" denip reddediliyordu.
-- Kullanici acisindan tutarsizdi: kaydi baslatmak icin once kanal ayarini
-- degistirmek gerekiyordu ve bu, kaydin kacirilmasi demekti.
--
-- Simdi kayit sirasinda MediaMTX'te kayit ACILIYOR, durdurulunca geri
-- kapatiliyor. Hangi kayitlarin bunu yaptigini bilmek zorundayiz; aksi halde
-- kullanicinin hic istemedigi bir kanal sonsuza kadar diske yazmaya devam
-- ederdi.
ALTER TABLE active_recordings
    ADD COLUMN dvr_bizden BOOLEAN NOT NULL DEFAULT FALSE;
