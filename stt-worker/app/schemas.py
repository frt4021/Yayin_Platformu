"""HTTP arayüzünün veri şekilleri."""

from pydantic import BaseModel, Field


class TranscriptionResult(BaseModel):
    """
    Bir konuşma bölütünün çözümlenmiş hali.

    `text` her zaman **İngilizce**: Whisper `task=translate` ile çalışıyor ve
    kaynak dil ne olursa olsun İngilizce üretiyor. Diğer diller bu metinden
    çeviriliyor.
    """

    source_language: str = Field(description="Whisper'ın tespit ettiği dil, örn. 'tr'")
    source_language_confidence: float = Field(description="Tespit güveni, [0,1]")

    text: str = Field(description="İngilizce metin — pivot")
    translations: dict[str, str] = Field(
        default_factory=dict,
        description="Hedef dil kodundan metne, örn. {'tr': '...', 'de': '...'}",
    )

    audio_ms: int = Field(description="Bölütün ses uzunluğu")
    processing_ms: int = Field(description="Çözümleme + çeviri süresi")

    def realtime_factor(self) -> float:
        """
        Kaç kat gerçek zamanda çalışıldı.

        Kapasite planlamasının tek sayısı: 20 kanal kesintisiz altyazı için
        toplamda 20x gerekiyor (VAD sessizlikleri attıktan sonra daha az).
        """
        return self.audio_ms / self.processing_ms if self.processing_ms else 0.0


class HealthStatus(BaseModel):
    ready: bool
    model: str
    device: str
    compute_type: str
    target_languages: list[str]
    loaded_translation_models: list[str]
