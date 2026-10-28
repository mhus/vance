package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.process.ProcessSpec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Argv of {@link ProcessIsolation} — pure string work, so it runs where bwrap does
 * not exist (macOS).
 */
class ProcessIsolationTest {

    private static final Path HOME = Path.of("/homes/acme/p1");
    private static final Path WORK = Path.of("/ws/acme/p1/_jsengine");

    private ExecProperties.Isolation iso;
    private ExecProperties.ProcessLimits limits;

    @BeforeEach
    void setUp() {
        iso = new ExecProperties.Isolation();
        iso.setMode("custom");
        iso.setProcessWrapper("bwrap --tmpfs /tmp {ro-binds} --bind {home} {home} --chdir {workdir} {binds} {net}");
        limits = new ExecProperties.ProcessLimits();
    }

    @Test
    void modeNone_runsTheArgvUnwrapped() {
        iso.setMode("none");

        assertThat(argv(ProcessSpec.of(List.of("npm", "--version"), WORK))).containsExactly("npm", "--version");
    }

    @Test
    void blankWrapper_runsTheArgvUnwrapped() {
        iso.setProcessWrapper("  ");

        assertThat(ProcessIsolation.enabled(iso)).isFalse();
        assertThat(argv(ProcessSpec.of(List.of("true"), WORK))).containsExactly("true");
    }

    @Test
    void withoutNetwork_unsharesTheNetwork_andAppendsTheArgv() {
        assertThat(argv(ProcessSpec.of(List.of("npm", "init", "-y"), WORK)))
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
        assertThat(argv(ProcessSpec.of(List.of("yt-dlp"), WORK).withNetwork())).doesNotContain("--unshare-net");
    }

    @Test
    void declaredPaths_becomeOneBindEach() {
        ProcessSpec spec = ProcessSpec.of(List.of("python3"), WORK)
                .withReadable(List.of(Path.of("/tmp/script.py"), Path.of("/tmp/a.mp3")))
                .withWritable(List.of(Path.of("/tmp/cache")));

        List<String> argv = argv(spec);

        assertThat(String.join(" ", argv))
                .contains("--tmpfs /tmp --ro-bind /tmp/script.py /tmp/script.py --ro-bind /tmp/a.mp3 /tmp/a.mp3 --bind")
                .contains("--chdir " + WORK + " --bind /tmp/cache /tmp/cache --unshare-net python3");
    }

    @Test
    void relativePaths_areBoundAbsolute() {
        ProcessSpec spec = ProcessSpec.of(List.of("ffmpeg"), WORK).withWritable(List.of(Path.of("out/../x.mp3")));

        assertThat(argv(spec)).contains(Path.of("x.mp3").toAbsolutePath().toString());
    }

    @Test
    void pathWithSpaces_staysOneArgvElement() {
        ProcessSpec spec = ProcessSpec.of(List.of("ffmpeg"), WORK).withWritable(List.of(Path.of("/tmp/a b.mp3")));

        assertThat(argv(spec)).containsSubsequence("--bind", "/tmp/a b.mp3", "/tmp/a b.mp3");
    }

    @Test
    void customPerPathArgs_replaceTheBubblewrapDefaults() {
        iso.setBindArgs("-v {path}:{path}:rw");
        iso.setNoNetworkArgs("--network none");
        ProcessSpec spec = ProcessSpec.of(List.of("x"), WORK).withWritable(List.of(Path.of("/data")));

        assertThat(argv(spec)).containsSubsequence("-v", "/data:/data:rw", "--network", "none", "x");
    }

    @Test
    void limits_prefixPrlimitOutsideTheWrapper() {
        limits.setAddressSpace(DataSize.ofMegabytes(256));
        limits.setCpuSeconds(60L);
        limits.setFileSize(DataSize.ofMegabytes(10));

        List<String> argv = argv(ProcessSpec.of(List.of("true"), WORK));

        assertThat(argv.subList(0, 5))
                .containsExactly("prlimit", "--as=268435456", "--cpu=60", "--fsize=10485760", "bwrap");
        assertThat(argv).last().isEqualTo("true");
    }

    @Test
    void limits_applyWithoutAWrapperToo() {
        iso.setMode("none");
        limits.setCpuSeconds(5L);

        assertThat(argv(ProcessSpec.of(List.of("true"), WORK))).containsExactly("prlimit", "--cpu=5", "true");
    }

    private List<String> argv(ProcessSpec spec) {
        return ProcessIsolation.argv(spec, HOME, WORK, iso, limits);
    }
}
