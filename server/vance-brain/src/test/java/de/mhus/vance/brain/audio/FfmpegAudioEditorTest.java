package de.mhus.vance.brain.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import de.mhus.vance.brain.ai.audio.AudioMimeTypeSniffer;
import de.mhus.vance.brain.ai.audio.PcmWav;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Golden tests for {@link FfmpegAudioEditor} against real ffmpeg:
 * deterministic sine waves generated in-process (no fixture files),
 * probed back with ffprobe. Durations carry a small tolerance — codec
 * frame rounding is real, especially for mp3.
 *
 * <p>The whole class is skipped when ffmpeg/ffprobe are not on the
 * PATH (developer machines without the toolchain, unusual CI
 * runners); the service-level pipeline is covered separately in
 * {@code AudioManipulationServiceTest}.
 */
@EnabledIf("ffmpegAvailable")
class FfmpegAudioEditorTest {

    private static final int SAMPLE_RATE = 24000;

    private final FfmpegAudioEditor editor = new FfmpegAudioEditor("ffmpeg", "ffprobe", 120);

    @Test
    void probe_reports_duration_and_format() {
        byte[] wav = sineWav(2.0, 440);

        FfmpegAudioEditor.ProbeData data = editor.probe(wav);

        assertThat(data.durationSeconds()).isCloseTo(2.0, within(0.05));
        assertThat(data.format()).isEqualTo("wav");
        assertThat(data.codec()).isEqualTo("pcm_s16le");
        assertThat(data.channels()).isEqualTo(1);
        assertThat(data.sampleRate()).isEqualTo(SAMPLE_RATE);
    }

    @Test
    void trim_cuts_exact_window() {
        byte[] wav = sineWav(2.0, 440);

        byte[] cut = editor.trim(wav, 0.5, 1.5, 0, 0, "wav");

        FfmpegAudioEditor.ProbeData data = editor.probe(cut);
        assertThat(data.durationSeconds()).isCloseTo(1.0, within(0.1));
    }

    @Test
    void trim_without_end_cuts_to_the_end() {
        byte[] wav = sineWav(2.0, 440);

        byte[] cut = editor.trim(wav, 1.0, null, 0, 0, "wav");

        assertThat(editor.probe(cut).durationSeconds()).isCloseTo(1.0, within(0.1));
    }

    @Test
    void trim_with_fades_keeps_window_length() {
        byte[] wav = sineWav(2.0, 440);

        byte[] cut = editor.trim(wav, 0.5, 1.5, 0.2, 0.2, "wav");

        assertThat(editor.probe(cut).durationSeconds()).isCloseTo(1.0, within(0.1));
    }

    @Test
    void trim_to_mp3_re_encodes() {
        byte[] wav = sineWav(1.0, 440);

        byte[] cut = editor.trim(wav, 0.0, null, 0, 0, "mp3");

        assertThat(AudioMimeTypeSniffer.sniff(cut, "application/octet-stream")).isEqualTo("audio/mpeg");
    }

    @Test
    void mix_follows_base_duration() {
        byte[] base = sineWav(2.0, 440);
        byte[] overlay = sineWav(0.5, 660);

        byte[] mix = editor.mix(base, overlay, 1.0, 0.3, 0.5, false, 0, "wav");

        assertThat(editor.probe(mix).durationSeconds()).isCloseTo(2.0, within(0.15));
    }

    @Test
    void mix_with_ducking_and_fade_out_runs() {
        byte[] base = sineWav(2.0, 440);
        byte[] overlay = sineWav(2.0, 220);

        byte[] mix = editor.mix(base, overlay, 1.0, 1.0, 0, true, 0.5, "wav");

        assertThat(editor.probe(mix).durationSeconds()).isCloseTo(2.0, within(0.15));
    }

    @Test
    void mix_rejects_fade_out_longer_than_base() {
        byte[] base = sineWav(2.0, 440);
        byte[] overlay = sineWav(0.5, 660);

        assertThatThrownBy(() -> editor.mix(base, overlay, 1.0, 0.3, 0, false, 2.5, "wav"))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void concat_joins_clips() {
        byte[] a = sineWav(1.0, 440);
        byte[] b = sineWav(1.0, 660);

        byte[] joined = editor.concat(List.of(a, b), 0, "wav");

        assertThat(editor.probe(joined).durationSeconds()).isCloseTo(2.0, within(0.15));
    }

    @Test
    void concat_crossfade_shortens_total() {
        byte[] a = sineWav(1.0, 440);
        byte[] b = sineWav(1.0, 660);

        byte[] joined = editor.concat(List.of(a, b), 0.5, "wav");

        assertThat(editor.probe(joined).durationSeconds()).isCloseTo(1.5, within(0.2));
    }

    @Test
    void concat_rejects_single_clip() {
        byte[] a = sineWav(1.0, 440);

        assertThatThrownBy(() -> editor.concat(List.of(a), 0, "wav"))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PARAMETER_INVALID);
    }

    @Test
    void convert_downsamples_and_downmixes() {
        byte[] wav = sineWav(1.0, 440);

        byte[] out = editor.convert(wav, 16000, 1, null, false, "wav");

        FfmpegAudioEditor.ProbeData data = editor.probe(out);
        assertThat(data.sampleRate()).isEqualTo(16000);
        assertThat(data.channels()).isEqualTo(1);
        assertThat(data.durationSeconds()).isCloseTo(1.0, within(0.1));
    }

    @Test
    void convert_to_mp3_with_normalise_re_encodes() {
        byte[] wav = sineWav(1.0, 440);

        byte[] out = editor.convert(wav, null, null, 128, true, "mp3");

        assertThat(AudioMimeTypeSniffer.sniff(out, "application/octet-stream")).isEqualTo("audio/mpeg");
        assertThat(editor.probe(out).durationSeconds()).isCloseTo(1.0, within(0.25));
    }

    @Test
    void corrupt_input_fails_with_processing_error() {
        byte[] junk = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};

        assertThatThrownBy(() -> editor.probe(junk))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.PROCESSING_ERROR);
    }

    /**
     * The wall-clock timeout must fire for a process that never exits:
     * reading the process output to EOF on the calling thread (the old
     * pattern) would block forever — a hung ffmpeg could never be killed.
     * The hanging "binary" is a shell script that ignores its arguments
     * and sleeps; probe runs it as ffprobe.
     */
    @Test
    @EnabledIf("unixShellAvailable")
    void hanging_process_is_killed_by_the_wall_clock_timeout() throws Exception {
        Path hang = Files.createTempFile("vance-audio-hang-", ".sh");
        try {
            Files.writeString(hang, "#!/bin/sh\nexec sleep 30\n");
            Files.setPosixFilePermissions(hang, PosixFilePermissions.fromString("rwxr-xr-x"));
            FfmpegAudioEditor hanging = new FfmpegAudioEditor("ffmpeg", hang.toString(), 1);
            long start = System.currentTimeMillis();

            assertThatThrownBy(() -> hanging.probe(new byte[0]))
                    .isInstanceOfSatisfying(AudioManipulationException.class, e -> {
                        assertThat(e.getReason()).isEqualTo(AudioManipulationException.Reason.PROCESSING_ERROR);
                        assertThat(e.getMessage()).contains("timed out after 1s");
                    });
            // The timeout fired around the 1 s deadline — not the sleep's 30 s.
            assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
        } finally {
            Files.deleteIfExists(hang);
        }
    }

    @Test
    void unknown_output_format_is_rejected() {
        byte[] wav = sineWav(0.5, 440);

        assertThatThrownBy(() -> editor.trim(wav, 0, null, 0, 0, "ogg"))
                .isInstanceOf(AudioManipulationException.class)
                .extracting(e -> ((AudioManipulationException) e).getReason())
                .isEqualTo(AudioManipulationException.Reason.FORMAT_UNSUPPORTED);
    }

    // ─────────────────── helpers ───────────────────

    /** Deterministic 16-bit mono sine, wrapped as WAV. */
    private static byte[] sineWav(double seconds, double freq) {
        int n = (int) Math.round(seconds * SAMPLE_RATE);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            short v = (short) (Math.sin(2 * Math.PI * freq * i / SAMPLE_RATE) * 12000);
            pcm[2 * i] = (byte) (v & 0xff);
            pcm[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        return PcmWav.wrap(pcm, SAMPLE_RATE, 1);
    }

    /** Condition for {@link EnabledIf}: both binaries answer. */
    static boolean ffmpegAvailable() {
        return runs("ffmpeg", "-version") && runs("ffprobe", "-version");
    }

    /** Condition for the timeout test's {@link EnabledIf}: a POSIX shell exists. */
    static boolean unixShellAvailable() {
        return runs("/bin/sh", "-c", "exit 0");
    }

    private static boolean runs(String... cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
