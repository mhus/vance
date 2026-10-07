package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.guard.ShootyGuardService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/** Unit coverage of the TOOL-point glue between dispatcher and Shooty. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolGuardGateTest {

    @Mock
    private ObjectProvider<ShootyGuardService> guardProvider;

    @Mock
    private ShootyGuardService guardService;

    @Mock
    private ThinkProcessService thinkProcessService;

    private ToolGuardGate gate;

    private final ToolInvocationContext ctx = new ToolInvocationContext("acme", "proj", "s1", "p1", "alice");

    @BeforeEach
    void setUp() {
        gate = new ToolGuardGate(guardProvider, thinkProcessService);
        when(guardProvider.getIfAvailable()).thenReturn(guardService);
        when(thinkProcessService.findById("p1"))
                .thenReturn(Optional.of(ThinkProcessDocument.builder()
                        .id("p1")
                        .tenantId("acme")
                        .projectId("proj")
                        .sessionId("s1")
                        .build()));
    }

    @Test
    void scope_coversEveryExecRunSurface() {
        assertThat(ToolGuardGate.guardsTool("exec_run")).isTrue();
        assertThat(ToolGuardGate.guardsTool("work_exec_run")).isTrue();
        assertThat(ToolGuardGate.guardsTool("client_exec_run")).isTrue();
        // Read/control exec tools don't carry a command; other families
        // are out of the v3.2 scope.
        assertThat(ToolGuardGate.guardsTool("exec_status")).isFalse();
        assertThat(ToolGuardGate.guardsTool("exec_tail")).isFalse();
        assertThat(ToolGuardGate.guardsTool("file_write")).isFalse();
        assertThat(ToolGuardGate.guardsTool("python_run")).isFalse();
    }

    @Test
    void gate_passesReasonThrough() {
        when(guardService.gateTool(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("dangerous command");

        assertThat(gate.gate("exec_run", Map.of("command", "rm -rf /"), ctx)).isEqualTo("dangerous command");
    }

    @Test
    void gate_denialNullMeansProceed() {
        when(guardService.gateTool(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);

        assertThat(gate.gate("exec_run", Map.of("command", "ls"), ctx)).isNull();
    }

    @Test
    void gate_nonExecTool_neverConsultsShooty() {
        assertThat(gate.gate("file_read", Map.of(), ctx)).isNull();
        verify(guardService, never())
                .gateTool(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void gate_headlessCall_neverConsultsShooty() {
        ToolInvocationContext headless = new ToolInvocationContext("acme", "proj", "", "", null);
        assertThat(gate.gate("exec_run", Map.of("command", "ls"), headless)).isNull();
        verify(thinkProcessService, never()).findById(anyString());
    }

    @Test
    void gate_unknownProcess_passes() {
        when(thinkProcessService.findById("p1")).thenReturn(Optional.empty());

        assertThat(gate.gate("exec_run", Map.of("command", "ls"), ctx)).isNull();
    }

    @Test
    void gate_guardSubsystemUnavailable_failsClosed() {
        when(guardProvider.getIfAvailable()).thenReturn(null);

        assertThat(gate.gate("exec_run", Map.of("command", "ls"), ctx)).contains("fail-closed");
    }

    @Test
    void gate_guardThrows_failsClosed() {
        when(guardService.gateTool(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("boom"));

        assertThat(gate.gate("exec_run", Map.of("command", "ls"), ctx)).contains("fail-closed");
    }
}
