package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.exec.ProcessLauncher;
import de.mhus.vance.exec.ScratchArea;
import de.mhus.vance.exec.WhisperEngine;
import de.mhus.vance.exec.YtDlpEngine;
import de.mhus.vance.runner.RunnerOperation.Call;
import de.mhus.vance.runner.RunnerOperation.OperationException;
import de.mhus.vance.runner.protocol.RunnerTokens;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code video.transcript} with stand-in yt-dlp and whisper processes: the wiring, not the programs. */
class VideoOperationsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RESULT = "{\"language\":\"en\",\"languageProbability\":0.8,\"durationSec\":6.0,"
            + "\"elapsedSec\":1.5,\"modelName\":\"base\",\"segments\":[{\"start\":0.0,\"end\":6.0,\"text\":\" hello there\"}]}";

    @TempDir
    Path tmp;

    private RunnerOperation operation;
    private String failYtDlp;
    private String failWhisper;
    private final RunnerTokens.Claims claims =
            new RunnerTokens.Claims("acme", "p", null, "jti", Instant.now().plusSeconds(60));
    private Path scratchRoot;

    @BeforeEach
    void setUp() throws Exception {
        failYtDlp = null;
        failWhisper = null;
        scratchRoot = tmp.resolve("scratch");
        ProcessLauncher launcher = spec -> {
            List<String> command = spec.command();
            if (command.getFirst().equals("yt-dlp")) {
                String out = command.get(command.indexOf("-o") + 1);
                String script = failYtDlp != null ? failYtDlp : "printf audio > \"$OUT\"";
                ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true);
                pb.environment().put("OUT", out);
                return pb.start();
            }
            // python3 <script> <audio> <model> <lang>
            return new ProcessBuilder("/bin/sh", "-c", failWhisper != null ? failWhisper : "echo '" + RESULT + "'")
                    .start();
        };
        operation = VideoOperations.all(
                        new YtDlpEngine(launcher), new WhisperEngine("python3", launcher), new ScratchArea(scratchRoot))
                .getFirst();
    }

    private JsonNode run(Map<String, Object> args) throws Exception {
        return operation
                .execute(new Call(claims, JSON.valueToTree(args), new ByteArrayInputStream(new byte[0])))
                .result();
    }

    @Test
    void aVideoIsDownloadedAndTranscribedInOneCall() throws Exception {
        JsonNode result = run(Map.of("videoId", "dQw4w9WgXcQ", "model", "base", "language", "en"));

        assertThat(result.get("language").asString()).isEqualTo("en");
        assertThat(result.get("durationSec").asDouble()).isEqualTo(6.0);
        assertThat(result.get("modelName").asString()).isEqualTo("base");
        assertThat(result.get("audioBytes").asLong()).isEqualTo(5); // "audio"
        assertThat(result.get("segments").get(0).get("text").asString()).isEqualTo(" hello there");
        assertThat(result.get("segments")).hasSize(1);
    }

    @Test
    void theAudioIsGoneAfterTheCall() throws Exception {
        run(Map.of("videoId", "dQw4w9WgXcQ"));

        try (var entries = Files.list(scratchRoot)) {
            assertThat(entries).isEmpty();
        }
    }

    @Test
    void onlyAnElevenCharacterVideoIdPasses_nothingThatCouldBecomeAnotherUrl() {
        for (String bad : List.of("short", "dQw4w9WgXcQ/../x", "dQw4w9WgXc;", "https://x.example/a", "dQw4w9WgXcQQ")) {
            assertThatThrownBy(() -> run(Map.of("videoId", bad)))
                    .isInstanceOfSatisfying(
                            OperationException.class, e -> assertThat(e.code()).isEqualTo("bad-args"));
        }
        assertThatThrownBy(() -> run(Map.of()))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("'videoId' is required"));
    }

    @Test
    void aFailureOfEitherStepCarriesItsReasonAndMessage() throws Exception {
        failYtDlp = "echo 'ERROR: video unavailable'; exit 1";
        assertThatThrownBy(() -> run(Map.of("videoId", "dQw4w9WgXcQ")))
                .isInstanceOfSatisfying(OperationException.class, e -> {
                    assertThat(e.code()).isEqualTo("processing_error");
                    assertThat(e.getMessage()).contains("video unavailable");
                });

        failYtDlp = null;
        failWhisper = "echo '{\"error\":\"model download failed\"}'";
        assertThatThrownBy(() -> run(Map.of("videoId", "dQw4w9WgXcQ")))
                .isInstanceOfSatisfying(
                        OperationException.class,
                        e -> assertThat(e.getMessage()).contains("model download failed"));
        try (var entries = Files.list(scratchRoot)) {
            assertThat(entries).isEmpty(); // a failed run cleans up too
        }
    }
}
