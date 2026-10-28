package de.mhus.vance.brain.tools.manual;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.brain.tools.PodCapabilities;
import de.mhus.vance.shared.workspace.WorkspaceMode;
import de.mhus.vance.shared.workspace.WorkspaceProperties;
import de.mhus.vance.toolpack.ToolCapabilities;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManualCapabilityGateTest {

    private static final String WORKSPACE_MANUAL =
            "---\nsummary: Python tooling\nrequires-capabilities: workspace\n---\n# Python\n";
    private static final String PLAIN_MANUAL = "---\nsummary: Documents\n---\n# Documents\n";

    @Test
    void requiredCapabilities_readsTheHeader() {
        assertThat(ManualCapabilityGate.requiredCapabilities(WORKSPACE_MANUAL)).containsExactly("workspace");
        assertThat(ManualCapabilityGate.requiredCapabilities(PLAIN_MANUAL)).isEmpty();
        assertThat(ManualCapabilityGate.requiredCapabilities("# no header at all"))
                .isEmpty();
    }

    @Test
    void available_workspaceOff_hidesOnlyManualsThatNeedIt() {
        ManualCapabilityGate gate = gate(WorkspaceMode.OFF);

        assertThat(gate.available(WORKSPACE_MANUAL)).isFalse();
        assertThat(gate.available(PLAIN_MANUAL)).isTrue();
    }

    @Test
    void available_workspaceLocal_showsEverything() {
        assertThat(gate(WorkspaceMode.LOCAL).available(WORKSPACE_MANUAL)).isTrue();
    }

    @Test
    void bundledAudioEditingManual_needsAudioTools() throws IOException {
        String manual;
        try (InputStream in = getClass().getResourceAsStream("/vance-defaults/_vance/manuals/audio-editing.md")) {
            assertThat(in).as("bundled audio-editing manual").isNotNull();
            manual = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WorkspaceProperties props = new WorkspaceProperties();

        assertThat(new ManualCapabilityGate(new PodCapabilities(props, List.of())).available(manual))
                .isFalse();
        assertThat(new ManualCapabilityGate(
                                new PodCapabilities(props, List.of(() -> Set.of(ToolCapabilities.AUDIO_TOOLS))))
                        .available(manual))
                .isTrue();
    }

    private static ManualCapabilityGate gate(WorkspaceMode mode) {
        WorkspaceProperties props = new WorkspaceProperties();
        props.setMode(mode);
        return new ManualCapabilityGate(new PodCapabilities(props, List.of()));
    }
}
