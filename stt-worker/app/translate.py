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

    async def translate(self, english: str) -> dict[str, str]:
        """
        İngilizce metni hedef dillere çevirir.

        İngilizce'nin kendisi **çeviri istemiyor** — Whisper'ın çıktısı zaten
        o; çağıran onu doğrudan kullanıyor.

        <p>Üç dil AYRI thread'lere dağıtılıp eşzamanlı çalıştırılıyor
        (anyio.to_thread + asyncio.gather) — sıralı çağrıldığında (eski
        davranış) üç dilin süresi toplanıyordu. Dil başına ayrı kilit
        (bkz. _translate_one) olduğu için bu güvenli: bir dilin çevirisi
        diğerini bloklamıyor.

        :return: dil kodundan metne. Bir dil başarısız olursa o dil sonuçta
                 **yer almaz**; diğerleri üretilir.
        """
        if not english.strip():
            return {}

        sentences = self._split(english)

        import anyio

        async def bir_dili_cevir(language, tokenizer, model):
            try:
                metin = await anyio.to_thread.run_sync(
                    self._translate_one, language, tokenizer, model, sentences)
                return language, metin
            except Exception as e:
                # Tek dilin hatasi TUM altyaziyi dusurmemeli: diger diller
                # uretilsin, eksik olan gorunsun.
                log.warning("Çeviri başarısız (EN → %s): %s", language.upper(), e)
                return language, None

        async with anyio.create_task_group() as tg:
            sonuclar: list[tuple[str, str | None]] = []

            async def calistir(language, tokenizer, model):
                sonuclar.append(await bir_dili_cevir(language, tokenizer, model))

            for language, (tokenizer, model) in self._models.items():
                tg.start_soon(calistir, language, tokenizer, model)

        return {lang: metin for lang, metin in sonuclar if metin is not None}

    # ------------------------------------------------------------------

    def _translate_one(self, language: str, tokenizer, model, sentences: list[str]) -> str:
        import torch

        # Cumleler TEK YIGINDA: tek tek cevirmek her cumle icin ayri bir
        # ileri gecis demek ve kisa cumlelerde ek yuk isin kendisinden
        # buyuk oluyor.
        with self._locks[language], torch.no_grad():
            batch = tokenizer(sentences, return_tensors="pt",
                              padding=True, truncation=True, max_length=512)
            batch = batch.to(SETTINGS.device)
            generated = model.generate(**batch, max_length=512, num_beams=1)
            parts = tokenizer.batch_decode(generated, skip_special_tokens=True)
        return " ".join(p.strip() for p in parts if p.strip())

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
