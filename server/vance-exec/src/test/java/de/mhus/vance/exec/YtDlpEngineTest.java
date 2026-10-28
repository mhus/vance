package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** yt-dlp's argv and failure handling, with a stand-in process instead of the network. */
class YtDlpEngineTest {

    @TempDir
    Path tmp;

    /** Answers like a yt-dlp that behaves as told: writes the file named after {@code -o}, or fails. */
    private static final class Fake implements ProcessLauncher {
        final List<ProcessSpec> specs = new ArrayList<>();
        String script = "printf audio > \"$OUT\"";

        @Override
        public Process start(ProcessSpec spec) throws IOException {
            specs.add(spec);
            List<String> command = spec.command();
            String out = command.get(command.indexOf("-o") + 1);
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true);
            pb.environment().put("OUT", out);
            return pb.start();
        }
    }

    @Test
    void theVideoIdBecomesTheUrl_andTheAudioLandsInTheOutDir() throws Exception {
        Fake launcher = new Fake();
        YtDlpEngine engine = new YtDlpEngine(launcher);

        Path audio = engine.download("dQw4w9WgXcQ", tmp);

        assertThat(audio).isEqualTo(tmp.resolve("yt-dQw4w9WgXcQ.mp3"));
        assertThat(Files.readString(audio)).isEqualTo("audio");
        ProcessSpec spec = launcher.specs.getFirst();
        assertThat(spec.command())
                .contains("yt-dlp", "-x", "--no-playlist", "--audio-format", "mp3")
                .last()
                .isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertThat(spec.network()).isTrue();
        assertThat(spec.writablePaths()).containsExactly(tmp);
        assertThat(spec.workingDir()).isEqualTo(tmp);
    }

    @Test
    void aFailureCarriesTheLastLineOfItsOutput() {
        Fake launcher = new Fake();
        launcher.script = "echo 'warming up'; echo 'ERROR: Sign in to confirm you are not a bot'; exit 1";

        assertThatThrownBy(() -> new YtDlpEngine(launcher).download("dQw4w9WgXcQ", tmp))
                .isInstanceOfSatisfying(MechException.class, e -> {
                    assertThat(e.reason()).isEqualTo(MechException.Reason.PROCESSING_ERROR);
                    assertThat(e.getMessage()).contains("exit 1").contains("Sign in to confirm");
                });
    }

    @Test
    void successWithoutAnOutputFileIsAnError() {
        Fake launcher = new Fake();
        launcher.script = "exit 0";

        assertThatThrownBy(() -> new YtDlpEngine(launcher).download("dQw4w9WgXcQ", tmp))
                .isInstanceOf(MechException.class)
                .hasMessageContaining("output file is missing");
    }

    @Test
    void aProgramThatCannotBeStartedIsReportedWithAHint() {
        ProcessLauncher missing = spec -> {
            throw new IOException("No such file or directory");
        };

        assertThatThrownBy(() -> new YtDlpEngine(missing).download("dQw4w9WgXcQ", tmp))
                .isInstanceOf(MechException.class)
                .hasMessageContaining("Failed to start yt-dlp")
                .hasMessageContaining("No such file");
    }

    @Test
    void availableAsksTheProgramOnce() {
        int[] asked = {0};
        ProcessLauncher launcher = spec -> {
            asked[0]++;
            return new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        };
        YtDlpEngine engine = new YtDlpEngine(launcher);

        assertThat(engine.available()).isTrue();
        assertThat(engine.available()).isTrue();
        assertThat(asked[0]).isEqualTo(1);
    }
}
