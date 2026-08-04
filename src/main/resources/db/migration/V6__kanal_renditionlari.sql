-- Kanal bazında çözünürlük merdiveni.
--
-- Global bir ayar yeterli değildi: her kaynağın bit hızı farklı ve merdivendeki
-- hedefler kaynağınkinin ALTINDA olmalı. Ölçümde 2.29 Mbps'lik bir kaynağa
-- 2500k'lık bir 720p rendition uygulandığında çıktı 2.51 Mbps oldu — çözünürlük
-- düştü ama bant genişliği arttı, yani saf israf.
--
-- Biçim: ad|GENISLIKxYUKSEKLIK|bithizi , virgülle ayrılır
--   720p|1280x720|1500k,480p|854x480|800k
--
-- Boş string = transcode yok, kaynak olduğu gibi dağıtılır (varsayılan).
alter table channels
    add column renditions varchar(512) not null default '';

comment on column channels.renditions is
    'Cozunurluk merdiveni: ad|GENISLIKxYUKSEKLIK|bithizi, virgulle ayrilir. Bos = transcode yok.';
