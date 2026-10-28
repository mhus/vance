package de.mhus.vance.brain.tools.exec;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ProcessIsolationSelfTest}: a wrapper that cannot start {@code true} aborts
 * the start with {@code mode=off} and only warns with {@code local}. {@code false}
 * as the "wrapper" fails every child; {@code env} passes it through.
 */
class ProcessIsolationSelfTestTest {

    @TempDir
    Path homesRoot;

    private ExecProperties props;
    private WorkspaceProperties workspace;
    private ProcessIsolationSelfTest selfTest;

    @BeforeEach
    void setUp() {
        props = new ExecProperties();
        props.getIsolation().setMode("custom");
        workspace = new WorkspaceProperties();
        SealedProcessLauncher launcher =
                new SealedProcessLauncher(new ExecEnvironmentBuilder(props, ExecTestHomes.homes(homesRoot)), props);
        selfTest = new ProcessIsolationSelfTest(launcher, workspace);
    }

    @Test
    void failingWrapper_withModeOff_abortsTheStart() {
        props.getIsolation().setProcessWrapper("false");
        workspace.setMode(WorkspaceMode.OFF);

        assertThatThrownBy(selfTest::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit 1");
    }

    @Test
    void missingWrapperBinary_withModeOff_abortsTheStart() {
        props.getIsolation().setProcessWrapper("/nonexistent/bwrap");
        workspace.setMode(WorkspaceMode.OFF);

        assertThatThrownBy(selfTest::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot start");
    }

    @Test
    void failingWrapper_withModeLocal_onlyWarns() {
        props.getIsolation().setProcessWrapper("false");
        workspace.setMode(WorkspaceMode.LOCAL);

        assertThatCode(selfTest::verify).doesNotThrowAnyException();
    }

    @Test
    void workingWrapper_withModeOff_passes() {
        props.getIsolation().setProcessWrapper("env");
        workspace.setMode(WorkspaceMode.OFF);

        assertThatCode(selfTest::verify).doesNotThrowAnyException();
    }

    @Test
    void noWrapper_withModeOff_passesWithAWarning() {
        workspace.setMode(WorkspaceMode.OFF);

        assertThatCode(selfTest::verify).doesNotThrowAnyException();
    }
}
