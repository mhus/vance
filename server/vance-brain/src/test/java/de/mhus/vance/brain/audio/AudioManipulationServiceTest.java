package de.mhus.vance.brain.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ai.audio.PcmWav;
import de.mhus.vance.brain.progress.ProgressEmitter;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link AudioManipulationService}. Mocks the
 * {@link DocumentService}, settings, progress + metrics and the
 * {@link FfmpegAudioEditor}; the service pipeline (load → validate →
 * write → result) is verified end to end, the engine itself is covered
 * by {@code FfmpegAudioEditorTest} against real ffmpeg.
 */
class AudioManipulationServiceTest {

    private DocumentService documentService;
    private SettingService settingService;
    private ProgressEmitter progressEmitter;
    private ThinkProcessService thinkProcessService;
    private MetricService metricService;
    private de.mhus.vance.brain.permission.SecurityContextFactory contextFactory;
    private FfmpegAudioEditor editor;

    private AudioManipulationService service;

    private byte[] twoSecondWav;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        settingService = mock(SettingService.class);
        progressEmitter = mock(ProgressEmitter.class);
        thinkProcessService = mock(ThinkProcessService.class);
        metricService = mock(MetricService.class);
        contextFactory = mock(de.mhus.vance.brain.permission.SecurityContextFactory.class);
        editor = mock(FfmpegAudioEditor.class);
        when(contextFactory.writeActor(any(), any(), any()))
                .thenReturn(de.mhus.vance.shared.permission.WriteActor.SYSTEM);

        when(settingService.getBooleanValueCascade(
                        anyString(), any(), any(), eq(AudioManipulationService.SETTING_ENABLED), anyBoolean()))
                .thenReturn(true);
        when(settingService.getStringValueCascade(anyString(), any(), any(), anyString()))
                .thenReturn(null);

        when(editor.probe(any())).thenReturn(new FfmpegAudioEditor.ProbeData(2.0, "wav", "pcm_s16le", 1, 24000));
        twoSecondWav = PcmWav.wrap(new byte[2 * 24000 * 2], 24000, 1);

        service = new AudioManipulationService(
                documentService,
                settingService,
                progressEmitter,
                thinkProcessService,
                metricService,
                contextFactory,
                editor);
    }

    // ─────────────────── probe ───────────────────

    @Test
    void probe_returns_metadata_without_write() {
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);

        AudioProbeResult result = service.probe(ProbeRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .path("audio/a.wav")
                .build());

        assertThat(result.path()).isEqualTo("audio/a.wav");
        assertThat(result.durationSeconds()).isEqualTo(2.0);
        assertThat(result.format()).isEqualTo("wav");
        assertThat(result.channels()).isEqualTo(1);
        assertThat(result.sampleRate()).isEqualTo(24000);
    }

    // ─────────────────── trim ───────────────────

    @Test
    void trim_writes_back_to_source_path_with_sniffed_mime() {
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);
        stubCreateOrReplaceBinaryEcho();
        when(editor.trim(any(), anyDouble(), any(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(twoSecondWav);

        AudioOpResult result = service.trim(TrimRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .processId("proc-1")
                .path("audio/a.wav")
                .startSeconds(0.5)
                .endSeconds(1.5)
                .build());

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verifyWrite("audio/a.wav", bytes, "audio/wav");
        assertThat(result.path()).isEqualTo("audio/a.wav");
        assertThat(result.mimeType()).isEqualTo("audio/wav");
        assertThat(result.sizeBytes()).isEqualTo(twoSecondWav.length);
        assertThat(result.durationSeconds()).isEqualTo(2.0);
        // Security: the write actor is built from the acting user + target path.
        org.mockito.Mockito.verify(contextFactory).writeActor(eq("acme"), eq("alice"), eq("audio/a.wav"));
    }

    @Test
    void trim_writes_to_target_path_when_given() {
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);
        stubCreateOrReplaceBinaryEcho();
        when(editor.trim(any(), anyDouble(), any(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(twoSecondWav);

        AudioOpResult result = service.trim(TrimRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .path("audio/a.wav")
                .targetPath("audio/cut.wav")
                .startSeconds(0.0)
                .build());

        assertThat(result.path()).isEqualTo("audio/cut.wav");
        org.mockito.Mockito.verify(documentService)
                .createOrReplaceBinary(
                        eq("acme"),
                        eq("p1"),
                        eq("audio/cut.wav"),
                        any(byte[].class),
                        eq("audio/wav"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(de.mhus.vance.shared.permission.WriteActor.class));
    }

    @Test
    void trim_rejects_inverted_window() {
        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .startSeconds(5.0)
                        .endSeconds(1.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void trim_rejects_fade_out_longer_than_cut() {
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .startSeconds(0.0)
                        .endSeconds(1.0)
                        .fadeOutSeconds(1.5)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void trim_rejects_fade_out_without_probeable_duration() {
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);
        when(editor.probe(any())).thenReturn(new FfmpegAudioEditor.ProbeData(null, "wav", "pcm_s16le", 1, 24000));

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .startSeconds(0.0)
                        .fadeOutSeconds(0.5)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void source_not_found() {
        stubFindByPathEmpty("audio/missing.wav");

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/missing.wav")
                        .startSeconds(0.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.SOURCE_NOT_FOUND);
    }

    @Test
    void not_audio_is_rejected() {
        DocumentDocument text = audioDoc("audio/a.txt", "text/plain", 10);
        stubFindByPath("audio/a.txt", text);
        stubLoadContent(text, "hello".getBytes());

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.txt")
                        .startSeconds(0.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.NOT_AUDIO);
    }

    @Test
    void disabled_scope_rejects() {
        when(settingService.getBooleanValueCascade(
                        anyString(), any(), any(), eq(AudioManipulationService.SETTING_ENABLED), anyBoolean()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .startSeconds(0.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.DISABLED);
    }

    @Test
    void input_byte_limit_exceeded() {
        when(settingService.getStringValueCascade(
                        anyString(), any(), any(), eq(AudioManipulationService.SETTING_MAX_INPUT_BYTES)))
                .thenReturn("100");
        DocumentDocument big = audioDoc("audio/big.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/big.wav", big);
        stubLoadContent(big, twoSecondWav);

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/big.wav")
                        .startSeconds(0.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.LIMIT_EXCEEDED);
    }

    @Test
    void output_byte_limit_exceeded_via_scope_cascade() {
        when(settingService.getStringValueCascade(
                        anyString(), any(), any(), eq(AudioManipulationService.SETTING_MAX_OUTPUT_BYTES)))
                .thenReturn("10");
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);
        when(editor.trim(any(), anyDouble(), any(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(twoSecondWav);

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .processId("proc-1")
                        .path("audio/a.wav")
                        .startSeconds(0.0)
                        .endSeconds(1.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.LIMIT_EXCEEDED);
    }

    @Test
    void duration_limit_exceeded() {
        when(settingService.getStringValueCascade(
                        anyString(), any(), any(), eq(AudioManipulationService.SETTING_MAX_DURATION_SECONDS)))
                .thenReturn("1");
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);

        assertThatThrownBy(() -> service.trim(TrimRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .startSeconds(0.0)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.LIMIT_EXCEEDED);
    }

    // ─────────────────── mix ───────────────────

    @Test
    void mix_requires_overlay_reference() {
        assertThatThrownBy(() -> service.mix(MixRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void mix_follows_base_and_writes_result() {
        DocumentDocument base = audioDoc("audio/voice.wav", "audio/wav", twoSecondWav.length);
        DocumentDocument overlay = audioDoc("audio/music.mp3", "audio/mpeg", 500);
        stubFindByPath("audio/voice.wav", base);
        stubFindByPath("audio/music.mp3", overlay);
        stubLoadContent(base, twoSecondWav);
        stubLoadContent(overlay, twoSecondWav);
        stubCreateOrReplaceBinaryEcho();
        when(editor.mix(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), anyDouble(), anyString()))
                .thenReturn(twoSecondWav);

        AudioOpResult result = service.mix(MixRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .path("audio/voice.wav")
                .overlayPath("audio/music.mp3")
                .overlayGain(0.3)
                .duckOverlay(true)
                .targetPath("audio/mix.wav")
                .build());

        assertThat(result.path()).isEqualTo("audio/mix.wav");
        // base is wav ⇒ output format wav ⇒ sniffed mime audio/wav.
        assertThat(result.mimeType()).isEqualTo("audio/wav");
    }

    @Test
    void mix_rejects_fade_out_longer_than_base() {
        DocumentDocument base = audioDoc("audio/voice.wav", "audio/wav", twoSecondWav.length);
        DocumentDocument overlay = audioDoc("audio/music.mp3", "audio/mpeg", 500);
        stubFindByPath("audio/voice.wav", base);
        stubFindByPath("audio/music.mp3", overlay);
        stubLoadContent(base, twoSecondWav);
        stubLoadContent(overlay, twoSecondWav);

        assertThatThrownBy(() -> service.mix(MixRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/voice.wav")
                        .overlayPath("audio/music.mp3")
                        .fadeOutSeconds(2.5)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void mix_rejects_fade_out_without_probeable_base_duration() {
        DocumentDocument base = audioDoc("audio/voice.wav", "audio/wav", twoSecondWav.length);
        DocumentDocument overlay = audioDoc("audio/music.mp3", "audio/mpeg", 500);
        stubFindByPath("audio/voice.wav", base);
        stubFindByPath("audio/music.mp3", overlay);
        stubLoadContent(base, twoSecondWav);
        stubLoadContent(overlay, twoSecondWav);
        when(editor.probe(any())).thenReturn(new FfmpegAudioEditor.ProbeData(null, "wav", "pcm_s16le", 1, 24000));

        assertThatThrownBy(() -> service.mix(MixRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/voice.wav")
                        .overlayPath("audio/music.mp3")
                        .fadeOutSeconds(0.5)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void mix_accepts_fade_out_shorter_than_base() {
        DocumentDocument base = audioDoc("audio/voice.wav", "audio/wav", twoSecondWav.length);
        DocumentDocument overlay = audioDoc("audio/music.mp3", "audio/mpeg", 500);
        stubFindByPath("audio/voice.wav", base);
        stubFindByPath("audio/music.mp3", overlay);
        stubLoadContent(base, twoSecondWav);
        stubLoadContent(overlay, twoSecondWav);
        stubCreateOrReplaceBinaryEcho();
        when(editor.mix(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), anyDouble(), anyString()))
                .thenReturn(twoSecondWav);

        AudioOpResult result = service.mix(MixRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .path("audio/voice.wav")
                .overlayPath("audio/music.mp3")
                .fadeOutSeconds(0.5)
                .build());

        assertThat(result.path()).isEqualTo("audio/voice.wav");
    }

    // ─────────────────── concat ───────────────────

    @Test
    void concat_requires_at_least_two_clips() {
        assertThatThrownBy(() -> service.concat(ConcatRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .paths(List.of("audio/a.wav"))
                        .targetPath("audio/out.wav")
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void concat_requires_exactly_one_reference_list() {
        assertThatThrownBy(() -> service.concat(ConcatRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .paths(List.of("audio/a.wav", "audio/b.wav"))
                        .documentIds(List.of("id-1", "id-2"))
                        .targetPath("audio/out.wav")
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void concat_rejects_target_that_is_one_of_the_clips() {
        DocumentDocument a = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        DocumentDocument b = audioDoc("audio/b.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", a);
        stubFindByPath("audio/b.wav", b);
        stubLoadContent(a, twoSecondWav);
        stubLoadContent(b, twoSecondWav);

        assertThatThrownBy(() -> service.concat(ConcatRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .paths(List.of("audio/a.wav", "audio/b.wav"))
                        .targetPath("audio/a.wav")
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.TARGET_BLOCKED);
    }

    @Test
    void concat_never_overwrites_without_explicit_target() {
        assertThatThrownBy(() -> service.concat(ConcatRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .paths(List.of("audio/a.wav", "audio/b.wav"))
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    // ─────────────────── convert ───────────────────

    @Test
    void convert_rejects_unknown_format() {
        assertThatThrownBy(() -> service.convert(ConvertRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .format("ogg")
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.FORMAT_UNSUPPORTED);
    }

    @Test
    void convert_rejects_bitrate_for_wav() {
        assertThatThrownBy(() -> service.convert(ConvertRequest.builder()
                        .tenantId("acme")
                        .projectId("p1")
                        .path("audio/a.wav")
                        .format("wav")
                        .bitrateKbps(192)
                        .build()))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void convert_writes_mp3_with_sniffed_mime() {
        DocumentDocument source = audioDoc("audio/a.wav", "audio/wav", twoSecondWav.length);
        stubFindByPath("audio/a.wav", source);
        stubLoadContent(source, twoSecondWav);
        stubCreateOrReplaceBinaryEcho();
        byte[] mp3 = mp3Bytes();
        when(editor.convert(any(), any(), any(), any(), anyBoolean(), anyString()))
                .thenReturn(mp3);

        AudioOpResult result = service.convert(ConvertRequest.builder()
                .tenantId("acme")
                .userId("alice")
                .projectId("p1")
                .path("audio/a.wav")
                .format("mp3")
                .normalize(true)
                .build());

        assertThat(result.path()).isEqualTo("audio/a.wav");
        // Sniffed from the written bytes — mp3 magic, not the source mime.
        assertThat(result.mimeType()).isEqualTo("audio/mpeg");
    }

    // ─────────────────── helpers ───────────────────

    private void verifyWrite(String path, ArgumentCaptor<byte[]> bytes, String mime) {
        org.mockito.Mockito.verify(documentService)
                .createOrReplaceBinary(
                        eq("acme"),
                        eq("p1"),
                        eq(path),
                        bytes.capture(),
                        eq(mime),
                        any(),
                        any(),
                        any(),
                        eq("alice"),
                        any(de.mhus.vance.shared.permission.WriteActor.class));
    }

    private DocumentDocument audioDoc(String path, String mime, long size) {
        DocumentDocument doc = new DocumentDocument();
        doc.setId("doc-" + path.hashCode());
        doc.setPath(path);
        doc.setMimeType(mime);
        doc.setSize(size);
        doc.setStorageId("storage-" + path.hashCode());
        return doc;
    }

    private void stubFindByPath(String path, DocumentDocument doc) {
        when(documentService.findByPath("acme", "p1", path)).thenReturn(Optional.of(doc));
    }

    private void stubFindByPathEmpty(String path) {
        when(documentService.findByPath("acme", "p1", path)).thenReturn(Optional.empty());
    }

    private void stubLoadContent(DocumentDocument source, byte[] bytes) {
        when(documentService.loadContent(source)).thenReturn(new ByteArrayInputStream(bytes));
    }

    private void stubCreateOrReplaceBinaryEcho() {
        when(documentService.createOrReplaceBinary(
                        any(),
                        any(),
                        any(),
                        any(byte[].class),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(de.mhus.vance.shared.permission.WriteActor.class)))
                .thenAnswer(inv -> {
                    String path = inv.getArgument(2);
                    byte[] bytes = inv.getArgument(3);
                    String mime = inv.getArgument(4);
                    DocumentDocument doc = new DocumentDocument();
                    doc.setId("written-" + path.hashCode());
                    doc.setPath(path);
                    doc.setMimeType(mime);
                    doc.setSize(bytes.length);
                    return doc;
                });
    }

    /** Minimal mp3-looking bytes (ID3 tag + mpeg frame magic) — the
     *  sniffer keys off these markers, no real decode needed. */
    private static byte[] mp3Bytes() {
        byte[] out = new byte[64];
        out[0] = 'I';
        out[1] = 'D';
        out[2] = '3';
        out[3] = 4;
        out[10] = 0;
        out[11] = (byte) 0xFF;
        out[12] = (byte) 0xFB;
        return out;
    }
}
