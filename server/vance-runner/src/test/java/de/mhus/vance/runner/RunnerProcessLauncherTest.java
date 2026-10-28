package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.exec.ProcessSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunnerProcessLauncherTest {

    @TempDir
    Path tmp;

    private String run(RunnerProcessLauncher launcher, ProcessSpec spec) throws Exception {
        Process process = launcher.start(spec);
        String out = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }

    @Test
    void aChildSeesTheSealedBaseAndAHomeOfItsProject_andNothingElseOfTheRunner() throws Exception {
        RunnerProcessLauncher launcher = new RunnerProcessLauncher(tmp, null);
        Path work = Files.createDirectories(tmp.resolve("work"));

        String env = run(launcher, ProcessSpec.of(List.of("/usr/bin/env"), work).withScope("acme", "kunde-x"));

        assertThat(env).contains("HOME=" + tmp.resolve("homes/acme/kunde-x"));
        assertThat(Files.isDirectory(tmp.resolve("homes/acme/kunde-x"))).isTrue();
        // Not inheritable: whatever else the runner process has stays with the runner.
        assertThat(env).doesNotContain("\nUSER=").doesNotContain("\nSHELL=").doesNotContain("JAVA_HOME=");
    }

    @Test
    void withoutAScopeTheHomeIsTheWorkingDirectory_andTheSpecsEnvironmentIsLayeredOnTop() throws Exception {
        RunnerProcessLauncher launcher = new RunnerProcessLauncher(tmp, null);
        Path work = Files.createDirectories(tmp.resolve("work2"));

        String env = run(
                launcher,
                ProcessSpec.of(List.of("/usr/bin/env"), work).withEnvironment(Map.of("GIT_TERMINAL_PROMPT", "0")));

        assertThat(env).contains("HOME=" + work).contains("GIT_TERMINAL_PROMPT=0");
    }

    @Test
    void aProcessWrapperTemplateIsApplied() throws Exception {
        // {workdir} and {home} fill in; the command follows the wrapper.
        RunnerProcessLauncher launcher =
                new RunnerProcessLauncher(tmp, "/usr/bin/env WRAPPED_IN={workdir} WRAPPED_HOME={home}");
        Path work = Files.createDirectories(tmp.resolve("work3"));

        String out = run(launcher, ProcessSpec.of(List.of("/usr/bin/env"), work).withScope("acme", "p"));

        assertThat(out).contains("WRAPPED_IN=" + work).contains("WRAPPED_HOME=" + tmp.resolve("homes/acme/p"));
    }

    @Test
    void aMissingProgramIsAnIoException() throws IOException {
        RunnerProcessLauncher launcher = new RunnerProcessLauncher(tmp, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> launcher.start(ProcessSpec.of(List.of("/no/such/program"), tmp)))
                .isInstanceOf(IOException.class);
    }
}
