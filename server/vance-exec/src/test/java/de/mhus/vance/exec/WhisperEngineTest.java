package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The whisper wrapper's protocol — JSON on stdout, PROGRESS lines on stderr — against a stand-in process. */
class WhisperEngineTest {

    @TempDir
    Path tmp;

    private static final class Fake implements ProcessLauncher {
        final List<ProcessSpec> specs = new ArrayList<>();
        String script;

        Fake(String script) {
            this.script = script;
        }

        @Override
        public Process start(ProcessSpec spec) throws IOException {
            specs.add(spec);
            return new ProcessBuilder("/bin/sh", "-c", script).start();
        }
    }

    private static final String RESULT = "{\"language\":\"de\",\"languageProbability\":0.9,\"durationSec\":10.5,"
            + "\"elapsedSec\":2.0,\"modelName\":\"small\",\"segments\":["
            + "{\"start\":0.0,\"end\":4.0,\"text\":\" Hallo\"},{\"start\":4.0,\"end\":10.5,\"text\":\" Welt\"}]}";

    @Test
    void theWrappersJsonBecomesAResult_andProgressLinesReachTheSink() throws Exception {
        Fake launcher = new Fake("echo 'PROGRESS chunkEndSec=4.0 durationSec=10.5' 1>&2;"
                + " echo 'PROGRESS chunkEndSec=10.5 durationSec=10.5' 1>&2; echo '" + RESULT + "'");
        WhisperEngine engine = new WhisperEngine("python3", launcher);
        List<double[]> progress = new ArrayList<>();

        WhisperEngine.Result result = engine.transcribe(
                tmp.resolve("a.mp3"), "small", "de", (end, total) -> progress.add(new double[] {end, total}));

        assertThat(result.language()).isEqualTo("de");
        assertThat(result.durationSec()).isEqualTo(10.5);
        assertThat(result.modelName()).isEqualTo("small");
        assertThat(result.segments())
                .containsExactly(
                        new WhisperEngine.Fragment(0.0, 4.0, " Hallo"), new WhisperEngine.Fragment(4.0, 10.5, " Welt"));
        assertThat(progress).hasSize(2);
        assertThat(progress.get(0)).containsExactly(4.0, 10.5);
    }

    @Test
    void modelAndLanguageDefault_andTheChildGetsTheNetworkAndItsFiles() throws Exception {
        Fake launcher = new Fake("echo '" + RESULT + "'");
        WhisperEngine engine = new WhisperEngine("python3", launcher);

        engine.transcribe(tmp.resolve("a.mp3"), null, null, null);

        ProcessSpec spec = launcher.specs.getFirst();
        List<String> command = spec.command();
        assertThat(command.getFirst()).isEqualTo("python3");
        assertThat(command.subList(command.size() - 2, command.size())).containsExactly("small", "auto");
        assertThat(spec.network()).isTrue();
        assertThat(spec.readablePaths()).contains(tmp.resolve("a.mp3"));
        assertThat(spec.redirectErrorStream()).isFalse(); // stderr carries progress, stdout the result
    }

    @Test
    void anErrorFromTheWrapperIsAnErrorOfTheEngine() throws Exception {
        WhisperEngine engine = new WhisperEngine("python3", new Fake("echo '{\"error\":\"model download failed\"}'"));

        assertThatThrownBy(() -> engine.transcribe(tmp.resolve("a.mp3"), "small", null, null))
                .isInstanceOfSatisfying(MechException.class, e -> {
                    assertThat(e.reason()).isEqualTo(MechException.Reason.PROCESSING_ERROR);
                    assertThat(e.getMessage()).contains("model download failed");
                });
    }

    @Test
    void aFailedExitCarriesTheLastLineOfStderr_noOutputAndGarbageAreErrors() throws Exception {
        assertThatThrownBy(() -> new WhisperEngine(
                                "python3", new Fake("echo 'Traceback...' 1>&2; echo 'ImportError: x' 1>&2; exit 2"))
                        .transcribe(tmp.resolve("a.mp3"), "small", null, null))
                .isInstanceOf(MechException.class)
                .hasMessageContaining("exit 2")
                .hasMessageContaining("ImportError: x");
        assertThatThrownBy(() -> new WhisperEngine("python3", new Fake("exit 0"))
                        .transcribe(tmp.resolve("a.mp3"), "small", null, null))
                .isInstanceOf(MechException.class)
                .hasMessageContaining("no output");
        assertThatThrownBy(() -> new WhisperEngine("python3", new Fake("echo 'not json'"))
                        .transcribe(tmp.resolve("a.mp3"), "small", null, null))
                .isInstanceOf(MechException.class)
                .hasMessageContaining("parse");
    }

    @Test
    void availableIsWhetherFasterWhisperImports_askedOnce() throws Exception {
        int[] asked = {0};
        WhisperEngine yes = new WhisperEngine("python3", spec -> {
            asked[0]++;
            return new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
        });
        WhisperEngine no = new WhisperEngine("python3", spec -> new ProcessBuilder("/bin/sh", "-c", "exit 1").start());

        assertThat(yes.available()).isTrue();
        assertThat(yes.available()).isTrue();
        assertThat(no.available()).isFalse();
        assertThat(asked[0]).isEqualTo(1);
    }

    @Test
    void theBundledScriptIsOnTheClasspath() throws Exception {
        // The runner's image has no other copy: the wrapper must travel with the engine.
        assertThat(WhisperEngine.class.getResourceAsStream("/scripts/transcribe.py"))
                .isNotNull();
    }
}
