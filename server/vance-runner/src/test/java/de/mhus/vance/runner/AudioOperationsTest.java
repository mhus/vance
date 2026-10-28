package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.RunnerOperation.OperationResult;
import de.mhus.vance.runner.protocol.RunnerTokens;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The audio operations against the real ffmpeg: sine waves made here, probed back by the runner itself. */
@EnabledIf("ffmpegAvailable")
class AudioOperationsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int RATE = 24000;

    @TempDir
    Path tmp;

    private RunnerWorkspace workspace;
    private final Map<String, RunnerOperation> ops = new HashMap<>();
    private final RunnerTokens.Claims claims =
            new RunnerTokens.Claims("acme", "p", null, "jti", Instant.now().plusSeconds(60));

    @BeforeEach
    void setUp() {
        workspace = new RunnerWorkspace(tmp, null);
        AudioOperations.all(workspace).forEach(op -> ops.put(op.name(), op));
    }

    @AfterEach
    void tearDown() {
        workspace.close();
    }

    private OperationResult run(String op, Map<String, Object> args, byte[]... inputs) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> sizes = new java.util.ArrayList<>();
        for (byte[] input : inputs) {
            sizes.add(input.length);
            body.writeBytes(input);
        }
        Map<String, Object> all = new HashMap<>(args);
        all.put("inputSizes", sizes);
        return ops.get(op)
                .execute(new Call(claims, JSON.valueToTree(all), new ByteArrayInputStream(body.toByteArray())));
    }

    private double duration(byte[] audio) throws Exception {
        return run("audio.probe", Map.of(), audio)
                .result()
                .get("durationSeconds")
                .asDouble();
    }

    @Test
    void probeReportsTheContainerAndStream() throws Exception {
        JsonNode probe = run("audio.probe", Map.of(), sineWav(1.0)).result();

        assertThat(probe.get("durationSeconds").asDouble()).isCloseTo(1.0, within(0.05));
        assertThat(probe.get("format").asString()).isEqualTo("wav");
        assertThat(probe.get("codec").asString()).isEqualTo("pcm_s16le");
        assertThat(probe.get("channels").asInt()).isEqualTo(1);
        assertThat(probe.get("sampleRate").asInt()).isEqualTo(RATE);
    }

    @Test
    void trimCutsAWindow_andAnEditedFileComesBackAsTheBody() throws Exception {
        OperationResult trimmed =
                run("audio.trim", Map.of("startSeconds", 0.2, "endSeconds", 0.8, "outputFormat", "wav"), sineWav(1.0));

        byte[] audio = trimmed.body().readAllBytes();
        assertThat(trimmed.result().get("bytes").asInt()).isEqualTo(audio.length);
        assertThat(duration(audio)).isCloseTo(0.6, within(0.05));
    }

    @Test
    void mixFollowsTheBase_concatJoinsTheClips_convertResamples() throws Exception {
        byte[] mixed = run("audio.mix", Map.of("overlayGain", 0.5, "outputFormat", "wav"), sineWav(1.0), sineWav(0.5))
                .body()
                .readAllBytes();
        byte[] joined = run("audio.concat", Map.of("outputFormat", "wav"), sineWav(0.5), sineWav(0.7))
                .body()
                .readAllBytes();
        byte[] converted = run(
                        "audio.convert", Map.of("sampleRate", 8000, "channels", 1, "outputFormat", "wav"), sineWav(1.0))
                .body()
                .readAllBytes();

        assertThat(duration(mixed)).isCloseTo(1.0, within(0.05));
        assertThat(duration(joined)).isCloseTo(1.2, within(0.05));
        assertThat(run("audio.probe", Map.of(), converted)
                        .result()
                        .get("sampleRate")
                        .asInt())
                .isEqualTo(8000);
    }

    @Test
    void aFailureCarriesTheAudioToolsReasonAsItsCode() throws Exception {
        assertThatThrownBy(() -> run("audio.trim", Map.of("outputFormat", "ogg"), sineWav(1.0)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("format_unsupported"));
        assertThatThrownBy(() -> run("audio.concat", Map.of("outputFormat", "wav"), sineWav(1.0)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("parameter_invalid"));
        assertThatThrownBy(() -> run("audio.probe", Map.of(), "this is not audio".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        OperationException.class, e -> assertThat(e.code()).isEqualTo("processing_error"));
    }

    @Test
    void theBodyMustBeExactlyWhatTheSizesSay() throws Exception {
        byte[] wav = sineWav(0.2);
        Map<String, Object> lying = Map.of("inputSizes", List.of(wav.length + 10));

        assertThatThrownBy(() -> ops.get("audio.probe")
                        .execute(new Call(claims, JSON.valueToTree(lying), new ByteArrayInputStream(wav))))
                .isInstanceOfSatisfying(OperationException.class, e -> {
                    assertThat(e.code()).isEqualTo("bad-args");
                    assertThat(e.getMessage()).contains("inputSizes");
                });
        assertThatThrownBy(() -> run("audio.mix", Map.of("outputFormat", "wav"), wav))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("two inputs"));
        assertThatThrownBy(() -> run("audio.probe", Map.of(), wav, wav))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("one input"));
    }

    @Test
    void aRunnerWithFfmpegReportsTheAudioToolsCapability() {
        RunnerConfig config = new RunnerConfig("r", "acme", 0, tmp, tmp.resolve("id"), tmp.resolve("k"), 1024);

        var operations = RunnerMain.operations(config, workspace);

        assertThat(operations.stream().map(RunnerOperation::name)).contains("audio.probe", "audio.convert", "info");
        assertThat(workspace.ffmpeg().available()).isTrue();
    }

    private static byte[] sineWav(double seconds) {
        int n = (int) Math.round(seconds * RATE);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (int i = 0; i < n; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / RATE) * 12000);
            pcm.write(v & 0xff);
            pcm.write((v >> 8) & 0xff);
        }
        byte[] data = pcm.toByteArray();
        ByteArrayOutputStream wav = new ByteArrayOutputStream();
        writeAscii(wav, "RIFF");
        writeInt(wav, 36 + data.length);
        writeAscii(wav, "WAVEfmt ");
        writeInt(wav, 16);
        writeShort(wav, 1);
        writeShort(wav, 1);
        writeInt(wav, RATE);
        writeInt(wav, RATE * 2);
        writeShort(wav, 2);
        writeShort(wav, 16);
        writeAscii(wav, "data");
        writeInt(wav, data.length);
        wav.writeBytes(data);
        return wav.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < 4; i++) {
            out.write((value >> (8 * i)) & 0xff);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    static boolean ffmpegAvailable() {
        return runs("ffmpeg") && runs("ffprobe");
    }

    private static boolean runs(String program) {
        try {
            Process p = new ProcessBuilder(program, "-version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
