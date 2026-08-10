package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The dispatcher attaches a failing tool's troubleshooting hint to the
 * {@link ToolException} rather than prepending it to the message.
 *
 * <p>It used to build {@code "hint: <hint> -- <failure>"}, so the tool
 * result the model received opened with advice; a failed
 * {@code client_file_edit} was consequently reported to the user as
 * done. The failure text must stand alone, with the hint reachable
 * separately for {@link ToolErrorPayload}.
 */
class ToolDispatcherHintTest {

    private ToolDispatcher dispatcher;
    private Tool failing;

    @BeforeEach
    void setUp() {
        PermissionService permissions =
                new PermissionService(List.of(new RecordingPermissionResolver()));

        failing = mock(Tool.class);
        when(failing.name()).thenReturn("fake.edit");
        when(failing.troubleshootingHint())
                .thenReturn("file missing = file_read first");
        when(failing.invoke(any(), any()))
                .thenThrow(new ToolException("Edit failed: no such file /tmp/x.vue"));

        ToolSource src = mock(ToolSource.class);
        when(src.find(eq("fake.edit"), any())).thenReturn(Optional.of(failing));
        when(src.sourceId()).thenReturn("test");

        dispatcher = new ToolDispatcher(List.of(src), permissions,
                mock(de.mhus.vance.brain.agrajag.AgrajagChecker.class),
                mock(de.mhus.vance.shared.toolhealth.ToolHealthService.class),
                mock(de.mhus.vance.shared.team.TeamService.class));
    }

    private ToolInvocationContext ctx() {
        return new ToolInvocationContext("acme", "proj", "sess-1", "p-1", "alice");
    }

    @Test
    void invoke_toolFails_messageStaysTheFailure_hintTravelsSeparately() {
        assertThatThrownBy(() -> dispatcher.invoke("fake.edit", Map.of(), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessage("Edit failed: no such file /tmp/x.vue")
                .satisfies(e -> assertThat(((ToolException) e).getHint())
                        .isEqualTo("file missing = file_read first"));
    }

    @Test
    void invoke_toolWithoutHint_passesTheExceptionThroughUnchanged() {
        when(failing.troubleshootingHint()).thenReturn(null);

        assertThatThrownBy(() -> dispatcher.invoke("fake.edit", Map.of(), ctx()))
                .isInstanceOf(ToolException.class)
                .hasMessage("Edit failed: no such file /tmp/x.vue")
                .satisfies(e -> assertThat(((ToolException) e).getHint()).isNull());
    }

    @Test
    void invoke_renderedForTheModel_leadsWithTheFailureNotTheHint() {
        ToolException raised = null;
        try {
            dispatcher.invoke("fake.edit", Map.of(), ctx());
        } catch (ToolException e) {
            raised = e;
        }
        assertThat(raised).isNotNull();

        String json = ToolErrorPayload.json(new tools.jackson.databind.ObjectMapper(), raised);

        assertThat(json).startsWith("{\"ok\":false,\"error\":\"" + ToolErrorPayload.FAILURE_PREFIX);
        assertThat(json.indexOf("Edit failed")).isLessThan(json.indexOf("file_read first"));
    }
}
