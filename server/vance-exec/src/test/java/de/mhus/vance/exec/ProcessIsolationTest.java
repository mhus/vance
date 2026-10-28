package de.mhus.vance.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Argv of {@link ProcessIsolation} — pure string work, so it runs where bwrap does
 * not exist (macOS).
 */
class ProcessIsolationTest {

    private static final Path HOME = Path.of("/homes/acme/p1");
    private static final Path WORK = Path.of("/ws/acme/p1/_jsengine");

    private static final ProcessIsolation.Wrapper BWRAP = ProcessIsolation.Wrapper.bubblewrap(
            "bwrap --tmpfs /tmp {ro-binds} --bind {home} {home} --chdir {workdir} {binds} {net}");

    @Test
    void noWrapper_runsTheArgvUnwrapped() {
        assertThat(argv(ProcessSpec.of(List.of("npm", "--version"), WORK), ProcessIsolation.Wrapper.bubblewrap(null)))
                .containsExactly("npm", "--version");
    }

    @Test
    void blankWrapper_runsTheArgvUnwrapped() {
        ProcessIsolation.Wrapper blank = ProcessIsolation.Wrapper.bubblewrap("  ");

        assertThat(blank.enabled()).isFalse();
        assertThat(argv(ProcessSpec.of(List.of("true"), WORK), blank)).containsExactly("true");
    }

    @Test
    void withoutNetwork_unsharesTheNetwork_andAppendsTheArgv() {
        assertThat(argv(ProcessSpec.of(List.of("npm", "init", "-y"), WORK), BWRAP))
                .containsExactly(
                        "bwrap",
                        "--tmpfs",
                        "/tmp",
                        "--bind",
                        HOME.toString(),
                        HOME.toString(),
                        "--chdir",
                        WORK.toString(),
                        "--unshare-net",
                        "npm",
                        "init",
                        "-y");
    }

    @Test
    void withNetwork_keepsTheNetwork() {
        assertThat(argv(ProcessSpec.of(List.of("yt-dlp"), WORK).withNetwork(), BWRAP))
                .doesNotContain("--unshare-net");
    }

    @Test
    void declaredPaths_becomeOneBindEach() {
        ProcessSpec spec = ProcessSpec.of(List.of("python3"), WORK)
                .withReadable(List.of(Path.of("/tmp/script.py"), Path.of("/tmp/a.mp3")))
                .withWritable(List.of(Path.of("/tmp/cache")));

        assertThat(String.join(" ", argv(spec, BWRAP)))
                .contains("--tmpfs /tmp --ro-bind /tmp/script.py /tmp/script.py --ro-bind /tmp/a.mp3 /tmp/a.mp3 --bind")
                .contains("--chdir " + WORK + " --bind /tmp/cache /tmp/cache --unshare-net python3");
    }

    @Test
    void relativePaths_areBoundAbsolute() {
        ProcessSpec spec = ProcessSpec.of(List.of("ffmpeg"), WORK).withWritable(List.of(Path.of("out/../x.mp3")));

        assertThat(argv(spec, BWRAP)).contains(Path.of("x.mp3").toAbsolutePath().toString());
    }

    @Test
    void pathWithSpaces_staysOneArgvElement() {
        ProcessSpec spec = ProcessSpec.of(List.of("ffmpeg"), WORK).withWritable(List.of(Path.of("/tmp/a b.mp3")));

        assertThat(argv(spec, BWRAP)).containsSubsequence("--bind", "/tmp/a b.mp3", "/tmp/a b.mp3");
    }

    @Test
    void customPerPathArgs_replaceTheBubblewrapDefaults() {
        ProcessIsolation.Wrapper docker = new ProcessIsolation.Wrapper(
                "wrap {binds} {net}", "-v {path}:{path}:rw", "-v {path}:{path}:ro", "--network none");
        ProcessSpec spec = ProcessSpec.of(List.of("x"), WORK).withWritable(List.of(Path.of("/data")));

        assertThat(argv(spec, docker)).containsSubsequence("-v", "/data:/data:rw", "--network", "none", "x");
    }

    @Test
    void limits_prefixPrlimitOutsideTheWrapper() {
        ProcessIsolation.Limits limits = new ProcessIsolation.Limits("prlimit", 268_435_456L, 60L, 10_485_760L);

        List<String> argv = ProcessIsolation.argv(ProcessSpec.of(List.of("true"), WORK), HOME, WORK, BWRAP, limits);

        assertThat(argv.subList(0, 5))
                .containsExactly("prlimit", "--as=268435456", "--cpu=60", "--fsize=10485760", "bwrap");
        assertThat(argv).last().isEqualTo("true");
    }

    @Test
    void limits_applyWithoutAWrapperToo() {
        ProcessIsolation.Limits limits = new ProcessIsolation.Limits("prlimit", null, 5L, null);

        assertThat(ProcessIsolation.argv(
                        ProcessSpec.of(List.of("true"), WORK),
                        HOME,
                        WORK,
                        ProcessIsolation.Wrapper.bubblewrap(null),
                        limits))
                .containsExactly("prlimit", "--cpu=5", "true");
    }

    private static List<String> argv(ProcessSpec spec, ProcessIsolation.Wrapper wrapper) {
        return ProcessIsolation.argv(spec, HOME, WORK, wrapper, ProcessIsolation.Limits.NONE);
    }
}
