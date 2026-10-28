package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.exec.ExecEnvPolicy;
import de.mhus.vance.exec.ProcessSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SealedProcessLauncherTest {

    @TempDir
    Path homesRoot;

    @TempDir
    Path workDir;

    private ExecProperties props;
    private SealedProcessLauncher launcher;

    @BeforeEach
    void setUp() {
        props = new ExecProperties();
        props.getEnv().setAllow(List.of("JAVA_HOME"));
        launcher = new SealedProcessLauncher(new ExecEnvironmentBuilder(props, ExecTestHomes.homes(homesRoot)), props);
    }

    @Test
    void childEnvironment_containsOnlyThePolicyBaseHomeAndExtras() {
        Map<String, String> env = launcher.childEnvironment(
                ProcessSpec.of(List.of("true"), workDir).withEnvironment(Map.of("EXTRA", "1")));

        Set<String> permitted = new HashSet<>(ExecEnvPolicy.INHERITABLE_NAMES);
        permitted.addAll(List.of("JAVA_HOME", "HOME", "EXTRA"));
        assertThat(permitted).containsAll(env.keySet());
        assertThat(env).containsEntry("EXTRA", "1");
    }

    @Test
    void childEnvironment_withoutScope_usesTheWorkingDirAsHome() {
        Map<String, String> env = launcher.childEnvironment(ProcessSpec.of(List.of("true"), workDir));

        assertThat(env).containsEntry("HOME", workDir.toString());
    }

    @Test
    void childEnvironment_withoutScopeAndWorkingDir_usesAPrivateHomeNotTheTempDir() {
        Map<String, String> env = launcher.childEnvironment(ProcessSpec.of(List.of("true"), null));

        assertThat(Path.of(env.get("HOME")))
                .isEqualTo(Path.of(System.getProperty("java.io.tmpdir"), SealedProcessLauncher.UNSCOPED_HOME));
    }

    @Test
    void argv_withoutWorkingDir_bindsTheHomeAsWorkdir() {
        props.getIsolation().setMode("custom");
        props.getIsolation().setProcessWrapper("wrap {home} {workdir}");
        ProcessSpec spec = ProcessSpec.of(List.of("true"), null);

        List<String> argv = launcher.argv(spec, launcher.childEnvironment(spec));

        String home = Path.of(System.getProperty("java.io.tmpdir"), SealedProcessLauncher.UNSCOPED_HOME)
                .toString();
        assertThat(argv).containsExactly("wrap", home, home, "true");
    }

    @Test
    void start_runsInsideTheProcessWrapper() throws Exception {
        // `env` as the wrapper: it runs the appended argv, so the child's output
        // proves the argv went through the template.
        props.getIsolation().setMode("custom");
        props.getIsolation().setProcessWrapper("env WRAPPED={workdir}");

        Process p = launcher.start(ProcessSpec.of(List.of("env"), workDir));
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();

        assertThat(out).contains("WRAPPED=" + workDir);
        assertThat(launcher.isolated()).isTrue();
    }

    @Test
    void childEnvironment_withScope_usesTheScopesHome() {
        Map<String, String> env = launcher.childEnvironment(
                ProcessSpec.of(List.of("true"), workDir).withScope("acme", "proj-1"));

        assertThat(Path.of(env.get("HOME"))).startsWith(homesRoot);
    }

    @Test
    void start_childSeesNoVariableOutsideThePolicy() throws Exception {
        // `env` prints the child's real environment — the end-to-end proof that
        // the brain's own variables (credentials included) stay behind.
        Process p = launcher.start(ProcessSpec.of(List.of("env"), workDir));
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();

        Set<String> permitted = new HashSet<>(ExecEnvPolicy.INHERITABLE_NAMES);
        permitted.addAll(List.of("JAVA_HOME", "HOME"));
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                assertThat(permitted)
                        .as("child variable %s", line.substring(0, eq))
                        .contains(line.substring(0, eq));
            }
        }
    }

    @Test
    void start_unknownBinary_failsToStart() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> launcher.start(ProcessSpec.of(List.of("/nonexistent/binary"), workDir)))
                .isInstanceOf(IOException.class);
    }
}
