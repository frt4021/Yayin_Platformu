package org.example.video.subtitle;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.example.VAD.SileroVad;
import org.example.VAD.SpeechSegment;
import org.example.VAD.SpeechSegmenter;
import org.example.VAD.TritonClient;
import org.example.VAD.VadConfig;
import org.example.subtitle.WebVttWriter;
import org.example.video.MediaTools;
import org.example.video.VideoService;
import org.example.video.VideoStorage;
import org.example.video.VideoSubtitleStatus;
import org.example.video.entity.Video;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bir video işini üreten birim: yüklenen dosyadan ses çıkarır, VAD ile
 * bölütler, Triton'dan geçirir, WebVTT üretir. Ne zaman çalışacağına
 * {@link VideoSubtitleConsumer} karar verir.
 *
 * <p>Canlı boru hattıyla (VadService/ChannelVadWorker) PAYLAŞILAN parçalar:
 * {@link SpeechSegmenter}, {@link SileroVad}, {@link TritonClient} — üçü de
 * canlıdan bağımsız saf bileşenler. Paylaşılmayan: bu sınıfın kendisi,
 * çünkü burada tek bir sınırlı dosya baştan sona işleniyor (canlı gibi
 * sürekli/round-robin adil kuyruk gerekmiyor) ve zaman damgaları
 * {@link Instant#EPOCH}'tan itibaren dosya-göreli (canlının mutlak duvar
 * saatinin aksine — video için "izleyicinin geride olduğu" diye bir kavram
 * yok).
 *
 * <p><b>Hata videonun kendisini etkilemez.</b> Altyazı ikincil bir özellik;
 * başarısızlıkta yalnızca {@code subtitle_status=HATA} olur, video zaten
 * oynatılabilir durumda kalır. Bu yüzden — Clip/VideoWorker'ın aksine —
 * yeniden deneme YOK: tek seferlik, best-effort.
 */
@ApplicationScoped
public class VideoSubtitleWorker {

    private static final Logger LOG = Logger.getLogger(VideoSubtitleWorker.class);

    /**
     * Triton'daki hedef dil → model adı eşlemesi. {@code VadService.DIL_MODELLERI}
     * ile AYNI küme — Whisper pivotu sağladığı için yalnızca {@code EN → X} yönleri var.
     */
    private static final Map<String, String> DIL_MODELLERI = Map.of(
        "tr", "marian_en_tr",
        "de", "marian_en_de",
        "ru", "marian_en_ru"
    );

    @Inject
    VideoStorage storage;

    @Inject
    MediaTools media;

    @Inject
    TritonClient triton;

    @ConfigProperty(name = "vad.model-path")
    String modelPath;

    @Transactional
    boolean claim(UUID videoId) {
        Video video = Video.findById(videoId);
        if (video == null || video.subtitleStatus != VideoSubtitleStatus.BEKLIYOR) {
            return false;
        }
        video.subtitleStatus = VideoSubtitleStatus.ISLENIYOR;
        return true;
    }

    @Transactional
    List<UUID> claimBatch(int limit) {
        List<Video> batch = Video.lockNextPendingSubtitle(limit);
        batch.forEach(v -> v.subtitleStatus = VideoSubtitleStatus.ISLENIYOR);
        return batch.stream().map(v -> v.id).toList();
    }

    void process(UUID videoId) {
        Job job = loadJob(videoId);
        if (job == null) {
            return;
        }

        Path pcm = null;
        try {
            String source = storage.internalReadUrl(job.objectKey());
            pcm = media.extractAudio(source);

            List<SpeechSegment> segmentler = new ArrayList<>();
            SpeechSegmenter segmenter = new SpeechSegmenter(videoId, job.title(), segmentler::add);
            try (SileroVad vad = new SileroVad(modelPath)) {
                okuVeIsle(pcm, vad, segmenter);
            }
            segmenter.flush();

            Map<String, List<WebVttWriter.VttCue>> dilBazliCue = new HashMap<>();
            for (SpeechSegment segment : segmentler) {
                TritonClient.TranscribeResult sonuc = triton.transcribe(segment);
                if (sonuc == null || sonuc.pivotText().isBlank()) {
                    continue;
                }
                ekle(dilBazliCue, "en", segment, sonuc.pivotText());
                for (var entry : DIL_MODELLERI.entrySet()) {
                    String ceviri = triton.translate(entry.getValue(), sonuc.pivotText());
                    if (ceviri != null && !ceviri.isBlank()) {
                        ekle(dilBazliCue, entry.getKey(), segment, ceviri);
                    }
                }
            }

            List<String> uretilenDiller = new ArrayList<>();
            for (var entry : dilBazliCue.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                String vtt = WebVttWriter.yaz(entry.getValue());
                String key = VideoService.subtitleKeyFor(job.objectKey(), entry.getKey());
                storage.put(key,
                    new ByteArrayInputStream(vtt.getBytes(StandardCharsets.UTF_8)), "text/vtt");
                uretilenDiller.add(entry.getKey());
            }

            markReady(videoId, uretilenDiller.isEmpty() ? null : String.join(",", uretilenDiller));
            LOG.infof("Video altyazısı hazır: %s (%d dil, %d bölüt)",
                videoId, uretilenDiller.size(), segmentler.size());
        } catch (Exception e) {
            handleFailure(videoId, e);
        } finally {
            media.deleteQuietly(pcm);
        }
    }

    private void ekle(Map<String, List<WebVttWriter.VttCue>> hedef, String dil,
                      SpeechSegment segment, String text) {
        hedef.computeIfAbsent(dil, d -> new ArrayList<>())
            .add(new WebVttWriter.VttCue(
                Duration.between(Instant.EPOCH, segment.startedAt()),
                Duration.between(Instant.EPOCH, segment.endedAt()),
                text));
    }

    /**
     * PCM dosyasını kare kare okuyup VAD skoruyla segmentleyiciye besler —
     * {@code AudioStream.readFrame()}'in bir dosya üzerindeki karşılığı.
     * Zaman damgaları duvar saati DEĞİL: {@code frameIndex}'ten hesaplanan,
     * {@link Instant#EPOCH}'a göreli — video başından itibaren geçen süre.
     */
    private void okuVeIsle(Path pcm, SileroVad vad, SpeechSegmenter segmenter) throws IOException {
        byte[] frameBytes = new byte[VadConfig.FRAME_BYTES];
        float[] frame = new float[VadConfig.FRAME_SAMPLES];
        long frameIndex = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(pcm), VadConfig.FRAME_BYTES * 16))) {
            while (true) {
                try {
                    in.readFully(frameBytes);
                } catch (EOFException e) {
                    break;
                }
                ByteBuffer bb = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < VadConfig.FRAME_SAMPLES; i++) {
                    frame[i] = bb.getShort() / 32768f;
                }
                Instant frameStart = Instant.EPOCH.plusMillis(
                    frameIndex * VadConfig.FRAME_SAMPLES * 1000L / VadConfig.SAMPLE_RATE);
                float score = vad.score(frame);
                segmenter.accept(frame, score, frameStart);
                frameIndex++;
            }
        }
    }

    // ------------------------------------------------------------------

    private record Job(String objectKey, String title) {
    }

    @Transactional
    Job loadJob(UUID videoId) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return null;
        }
        return new Job(video.objectKey, video.title);
    }

    @Transactional
    void markReady(UUID videoId, String subtitleLangs) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return;
        }
        video.subtitleStatus = VideoSubtitleStatus.HAZIR;
        video.subtitleLangs = subtitleLangs;
        video.subtitleError = null;
    }

    @Transactional
    void handleFailure(UUID videoId, Exception cause) {
        Video video = Video.findById(videoId);
        if (video == null) {
            return;
        }
        video.subtitleStatus = VideoSubtitleStatus.HATA;
        video.subtitleError = cause.getMessage();
        LOG.warnf(cause, "Video altyazısı üretilemedi: %s", videoId);
    }
}
