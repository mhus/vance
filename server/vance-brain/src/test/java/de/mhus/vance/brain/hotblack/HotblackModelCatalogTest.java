package de.mhus.vance.brain.hotblack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.ModelCatalog;
import de.mhus.vance.brain.ai.ModelQuirks;
import de.mhus.vance.brain.ai.audio.AuModelInfo;
import de.mhus.vance.brain.ai.audio.MusicModelInfo;
import de.mhus.vance.brain.ai.audio.SttModelInfo;
import de.mhus.vance.brain.ai.audio.TtsModelInfo;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the bundled audio model documents (the {@code kind: tts/stt/music}
 * entries under {@code vance-defaults/_vance/model/**}) — same role as
 * {@code ModelCatalogTest} has for the image models: a refactoring that
 * silently drops or reparses one of them should fail here, not at call
 * time in front of a user.
 */
class HotblackModelCatalogTest {

    private DocumentService documentService;
    private ModelCatalog catalog;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        when(documentService.findAllByPathPrefix(ModelCatalog.MODEL_PATH_PREFIX))
                .thenReturn(List.<DocumentDocument>of());
        catalog = new ModelCatalog(documentService, new ModelQuirks());
    }

    @Test
    void bundled_tts_model_carries_its_voices_and_pricing() {
        TtsModelInfo info = catalog.lookupTts(null, null, "openrouter", "google/gemini-3.8-flash-lite-tts")
                .orElseThrow();

        assertThat(info.provider()).isEqualTo("openrouter");
        assertThat(info.supportedVoices()).extracting(TtsModelInfo.Voice::id).contains("Kore", "Puck");
        assertThat(info.supportedLanguages()).isEmpty(); // no gate = any language
        assertThat(info.supportsLanguage("de")).isTrue();
        assertThat(info.supportsFormat("pcm")).isTrue();
        assertThat(info.supportsFormat("mp3")).isFalse();
        assertThat(info.costPerChar()).isGreaterThan(0);
    }

    @Test
    void bundled_stt_model_exists_and_is_open_language() {
        SttModelInfo info = catalog.lookupStt(null, null, "openrouter", "openai/gpt-transcribe")
                .orElseThrow();

        assertThat(info.supportsLanguage(null)).isTrue();
        assertThat(info.supportsFormat("wav")).isTrue();
        assertThat(info.supportsFormat("mp3")).isTrue();
    }

    @Test
    void bundled_music_model_pins_the_clip_cap() {
        MusicModelInfo info = catalog.lookupMusic(null, null, "openrouter", "google/lyria-3-clip-preview")
                .orElseThrow();

        assertThat(info.maxDurationSeconds()).isEqualTo(300);
        assertThat(info.costPerTrack()).isEqualTo(0.04);
        assertThat(info.supportsLanguage("en")).isTrue();
    }

    @Test
    void bundled_au_models_pin_the_analysis_contract() {
        AuModelInfo voxtral = catalog.lookupAu(null, null, "openrouter", "mistralai/voxtral-small-24b-2507")
                .orElseThrow();
        assertThat(voxtral.supportsFormat("mp3")).isTrue();
        assertThat(voxtral.supportsFormat("wav")).isTrue();
        assertThat(voxtral.costPerSecond()).isEqualTo(0.0001);

        AuModelInfo gptAudio =
                catalog.lookupAu(null, null, "openrouter", "openai/gpt-audio").orElseThrow();
        assertThat(gptAudio.supportsFormat("wav")).isTrue();
        assertThat(gptAudio.maxDurationSeconds()).isEqualTo(1800);
    }

    @Test
    void bundled_local_whisper_models_are_stt() {
        assertThat(catalog.lookupStt(null, null, "local", "faster-whisper-small"))
                .isPresent();
        assertThat(catalog.lookupStt(null, null, "local", "faster-whisper-medium"))
                .isPresent();
    }

    @Test
    void kinds_are_disjunct() {
        // A tts entry must not leak into the stt/music lookups — the
        // pickers and the call-time gates rely on the disjunction.
        assertThat(catalog.lookupStt(null, null, "openrouter", "google/gemini-3.8-flash-lite-tts"))
                .isEmpty();
        assertThat(catalog.lookupMusic(null, null, "openrouter", "google/gemini-3.8-flash-lite-tts"))
                .isEmpty();
        assertThat(catalog.lookupTts(null, null, "openrouter", "openai/gpt-transcribe"))
                .isEmpty();
        // …au entries are their own pool, disjoint from stt/music/chat:
        // audio_analyze must never resolve to a transcription or music
        // model (and an au doc must not shadow a chat picker entry).
        assertThat(catalog.lookupAu(null, null, "openrouter", "openai/gpt-transcribe"))
                .isEmpty();
        assertThat(catalog.lookupAu(null, null, "openrouter", "google/lyria-3-clip-preview"))
                .isEmpty();
        assertThat(catalog.lookupStt(null, null, "openrouter", "mistralai/voxtral-small-24b-2507"))
                .isEmpty();
        assertThat(catalog.lookupMusic(null, null, "openrouter", "openai/gpt-audio"))
                .isEmpty();
        // …and audio entries never show up as chat models.
        assertThat(catalog.lookup(null, null, "openrouter", "google/lyria-3-clip-preview"))
                .isEmpty();
        assertThat(catalog.lookup(null, null, "openrouter", "mistralai/voxtral-small-24b-2507"))
                .isEmpty();
    }
}
