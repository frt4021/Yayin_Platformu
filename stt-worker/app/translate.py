"""
Opus-MT sarmalayıcısı — İngilizce'den hedef dillere.

Whisper pivotu sağladığı için burada yalnızca `EN → X` yönleri var. Kaynak
dil kümesi genişlese bile bu set **sabit kalıyor**; çevrimdışı kısıt altında
belirleyici kazanç bu.

Çeviri Whisper ile AYNI cihazda çalışır (`STT_DEVICE`). CPU'da ölçülen tavan
4,39x idi — üç dil tek kilitle seri çalıştığı için Whisper'ı GPU'ya alsan
bile çeviri darboğaz oluyordu.
"""

import logging
import re
import threading

from .config import SETTINGS

log = logging.getLogger(__name__)

# Marian modelleri cumle duzeyinde egitildi; uzun paragraf verildiginde sonu
# SESSIZCE kirpiliyor. Cumlelere bolup ayri ayri cevirmek hem daha dogru hem
# token sinirini asmiyor.
SENTENCE_BOUNDARY = re.compile(r"(?<=[.!?])\s+")

# Marian'in girdi siniri 512 token. Noktalamasiz uzun konusma tek "cumle"
# gorunuyor; kelime sinirindan bolup yarim kelime birakmiyoruz.
MAX_CHARS = 900


class Translator:
    """Hedef dil başına bir model, hepsi bellekte."""

    def __init__(self) -> None:
        self._models: dict[str, tuple] = {}
        # Dil basina AYRI kilit: TR cevirisi DE'yi bloklamasin. Ayni dilin
        # ardisik cagrilari hala seri -- ayni model nesnesine es zamanli
        # generate() cagrisi guvenli sayilmiyor.
        self._locks: dict[str, threading.Lock] = {}

    def load(self) -> None:
        """
        Hedef dillerin modellerini belleğe alır.

        Eksik model **sessizce atlanmıyor**: bir dilin modeli yoksa o dilde
        altyazı hiç üretilmez ve sebebi hiçbir yerde görünmez. Açılışta
        açıkça patlamak doğrusu.
        """
        from transformers import MarianMTModel, MarianTokenizer

        for language in SETTINGS.target_languages:
            path = SETTINGS.translation_path(language)
            log.info("Çeviri modeli yükleniyor: EN → %s (%s, %s)",
                     language.upper(), path, SETTINGS.device)
            # local_files_only=True SART -- kapali agda indirmeye kalkmasin.
            tokenizer = MarianTokenizer.from_pretrained(path, local_files_only=True)
            model = MarianMTModel.from_pretrained(path, local_files_only=True)
            model.eval()
            model.to(SETTINGS.device)
            self._models[language] = (tokenizer, model)
            self._locks[language] = threading.Lock()

    def loaded_languages(self) -> list[str]:
        return sorted(self._models.keys())

    async def translate_batch(self, texts: list[str]) -> list[dict[str, str]]:
        """
        Birden fazla BAĞIMSIZ metni (farklı kanallardan gelen bölütlerin
        Whisper çıktıları) her dil için TEK `model.generate()` çağrısında
        çevirir.

        <p>Marian bir metnin İÇİNDEKİ cümleleri zaten tek yığında çeviriyordu
        (bkz. `_translate_many`); burada aynı fikir kanallar arasına
        genişliyor — 5 farklı kanalın 5 ayrı metni, aynı dil için TEK
        tokenize+generate çağrısında birlikte çevriliyor. Sabit maliyet
        (kernel açma, dolgu/padding hazırlığı) 5 kez değil 1 kez ödeniyor.

        :param texts: her biri bir Whisper çıktısı, boş olabilir
        :return: ``texts`` ile AYNI sırada, dil kodundan metne sözlük —
                 boş girdi için `{}`, tek bir dil başarısız olursa o dil o
                 girdinin sözlüğünde yer almaz.
        """
        # Her metnin cumlelere bolunmus hali -- bos metin icin bos liste,
        # asagida bu metnin hicbir dile cevrilmeyecegini isaret ediyor.
        tumu = [self._split(t) if t.strip() else [] for t in texts]

        import anyio

        async def bir_dili_cevir(language, tokenizer, model):
            try:
                sonuclar = await anyio.to_thread.run_sync(
                    self._translate_many, language, tokenizer, model, tumu)
                return language, sonuclar
            except Exception as e:
                log.warning("Toplu çeviri başarısız (EN → %s): %s", language.upper(), e)
                return language, [None] * len(texts)

        outcomes: list[tuple[str, list[str | None]]] = []

        async with anyio.create_task_group() as tg:
            async def calistir(language, tokenizer, model):
                outcomes.append(await bir_dili_cevir(language, tokenizer, model))

            for language, (tokenizer, model) in self._models.items():
                tg.start_soon(calistir, language, tokenizer, model)

        results: list[dict[str, str]] = [{} for _ in texts]
        for language, sonuclar in outcomes:
            for i, metin in enumerate(sonuclar):
                if metin is not None and tumu[i]:  # bos metin -> hicbir dile girmesin
                    results[i][language] = metin
        return results

    # ------------------------------------------------------------------

    def _translate_many(self, language: str, tokenizer, model,
                         tumu: list[list[str]]) -> list[str]:
        """
        `tumu`'daki HER metnin cümlelerini TEK yığında çevirir, sonra hangi
        cümlenin hangi metne ait olduğuna göre geri böler.

        <p>Kilit şart: aynı model nesnesine eşzamanlı `generate()` çağrısı
        güvenli değil — dil başına ayrı kilit olduğu için farklı dillerin
        çevirisi birbirini bloklamıyor, aynı dilin ardışık batch'leri seri.
        """
        import torch

        # Duz bir cumle listesi + hangi metnin kac cumle katkida bulundugunu
        # gosteren sinirlar -- sonuc geldiginde bu sinirlarla geri bolunecek.
        duz_cumleler = [s for sentences in tumu for s in sentences]
        if not duz_cumleler:
            return ["" for _ in tumu]

        with self._locks[language], torch.no_grad():
            batch = tokenizer(duz_cumleler, return_tensors="pt",
                              padding=True, truncation=True, max_length=512)
            batch = batch.to(SETTINGS.device)
            girdi_mb = batch["input_ids"].element_size() * batch["input_ids"].nelement() / (1024 * 1024)
            log.info("CEVIRI BATCH dil=%s metin=%d cumle=%d girdi_tensoru=%.4f MB",
                      language, len(tumu), len(duz_cumleler), girdi_mb)
            generated = model.generate(**batch, max_length=512, num_beams=1)
            parts = tokenizer.batch_decode(generated, skip_special_tokens=True)

        sonuclar = []
        i = 0
        for sentences in tumu:
            n = len(sentences)
            sonuclar.append(" ".join(p.strip() for p in parts[i:i + n] if p.strip()))
            i += n
        return sonuclar

    @staticmethod
    def _split(text: str) -> list[str]:
        sentences = [s.strip() for s in SENTENCE_BOUNDARY.split(text.strip()) if s.strip()]
        if not sentences:
            return [text.strip()]

        out: list[str] = []
        for sentence in sentences:
            while len(sentence) > MAX_CHARS:
                cut = sentence.rfind(" ", 0, MAX_CHARS)
                if cut <= 0:
                    cut = MAX_CHARS
                out.append(sentence[:cut])
                sentence = sentence[cut:].lstrip()
            if sentence:
                out.append(sentence)
        return out
