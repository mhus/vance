package de.mhus.vance.brain.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SubjectType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the central permission gate in {@link ToolDispatcher#invoke}.
 *
 * <p>Every tool in the brain (60+ implementations) goes through this one
 * dispatcher, so checking the gate here covers all of them at once.
 */
class ToolDispatcherPermissionTest {

    private RecordingPermissionResolver recorder;
    private ToolDispatcher dispatcher;
    private Tool fakeTool;

    @BeforeEach
    void setUp() {
        recorder = new RecordingPermissionResolver();
        PermissionService permissions = new PermissionService(recorder);

        fakeTool = mock(Tool.class);
        when(fakeTool.name()).thenReturn("fake.tool");
        when(fakeTool.invoke(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("ok", true));

        ToolSource src = mock(ToolSource.class);
        when(src.find(org.mockito.ArgumentMatchers.eq("fake.tool"),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(fakeTool));
        when(src.sourceId()).thenReturn("test");

        dispatcher = new ToolDispatcher(List.of(src), permissions);
    }

    @Test
    void invoke_withFullProcessContext_recordsThinkProcessResource() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj", "sess-1", "p-1", "alice");

        dispatcher.invoke("fake.tool", Map.of(), ctx);

        assertThat(recorder.checks()).hasSize(1);
        RecordingPermissionResolver.Check check = recorder.lastCheck();
        assertThat(check.subject().subjectType()).isEqualTo(SubjectType.USER);
        assertThat(check.subject().subjectId()).isEqualTo("alice");
        assertThat(check.action()).isEqualTo(Action.EXECUTE);
        assertThat(check.resource())
                .isInstanceOf(Resource.ThinkProcess.class)
                .satisfies(r -> {
                    Resource.ThinkProcess tp = (Resource.ThinkProcess) r;
                    assertThat(tp.tenantId()).isEqualTo("acme");
                    assertThat(tp.projectName()).isEqualTo("proj");
                    assertThat(tp.sessionName()).isEqualTo("sess-1");
                    assertThat(tp.processId()).isEqualTo("p-1");
                });
    }

    @Test
    void invoke_withSessionContext_butNoProcess_recordsSessionResource() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj", "sess-1", null, "alice");

        dispatcher.invoke("fake.tool", Map.of(), ctx);

        assertThat(recorder.lastCheck().resource())
                .isInstanceOf(Resource.Session.class);
    }

    @Test
    void invoke_withProjectContext_butNoSession_recordsProjectResource() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj", null, null, "alice");

        dispatcher.invoke("fake.tool", Map.of(), ctx);

        assertThat(recorder.lastCheck().resource())
                .isInstanceOf(Resource.Project.class);
    }

    @Test
    void invoke_withTenantOnlyContext_recordsTenantResource() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", null, null, null, "alice");

        dispatcher.invoke("fake.tool", Map.of(), ctx);

        assertThat(recorder.lastCheck().resource())
                .isInstanceOf(Resource.Tenant.class);
    }

    @Test
    void invoke_withoutUserId_usesSystemSubject() {
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj", null, null, null);

        dispatcher.invoke("fake.tool", Map.of(), ctx);

        assertThat(recorder.lastCheck().subject().subjectType())
                .isEqualTo(SubjectType.SYSTEM);
    }

    @Test
    void invoke_throwsPermissionDenied_whenResolverDenies() {
        recorder.verdict(false);
        ToolInvocationContext ctx = new ToolInvocationContext(
                "acme", "proj", "sess-1", "p-1", "alice");

        assertThatThrownBy(() -> dispatcher.invoke("fake.tool", Map.of(), ctx))
                .isInstanceOf(PermissionDeniedException.class);
    }
}
