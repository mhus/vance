package de.mhus.vance.brain.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.RecordingPermissionResolver;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestAuthorityTest {

    private RecordingPermissionResolver recorder;
    private RequestAuthority authority;

    @BeforeEach
    void setUp() {
        recorder = new RecordingPermissionResolver();
        TeamService teamService = mock(TeamService.class);
        when(teamService.byMember("acme", "alice")).thenReturn(List.of());

        SecurityContextFactory factory = new SecurityContextFactory(teamService);
        PermissionService service = new PermissionService(recorder);
        authority = new RequestAuthority(service, factory);
    }

    @Test
    void enforce_request_buildsUserContext_andRecordsCheck() {
        HttpServletRequest request = mockRequestWith("alice", "acme");
        Resource resource = new Resource.Project("acme", "proj");

        authority.enforce(request, resource, Action.READ);

        assertThat(recorder.checks()).hasSize(1);
        RecordingPermissionResolver.Check check = recorder.lastCheck();
        assertThat(check.subject().subjectId()).isEqualTo("alice");
        assertThat(check.subject().tenantId()).isEqualTo("acme");
        assertThat(check.resource()).isEqualTo(resource);
        assertThat(check.action()).isEqualTo(Action.READ);
    }

    @Test
    void enforce_connection_buildsUserContext_andRecordsCheck() {
        ConnectionContext connection = new ConnectionContext(
                "acme", "alice", null, "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
        Resource resource = new Resource.Tenant("acme");

        authority.enforce(connection, resource, Action.START);

        assertThat(recorder.lastCheck().action()).isEqualTo(Action.START);
        assertThat(recorder.lastCheck().subject().subjectId()).isEqualTo("alice");
    }

    @Test
    void enforce_throws_onDeniedVerdict() {
        recorder.verdict(false);
        HttpServletRequest request = mockRequestWith("alice", "acme");

        assertThatThrownBy(() -> authority.enforce(request,
                new Resource.Project("acme", "proj"), Action.WRITE))
                .isInstanceOf(de.mhus.vance.shared.permission.PermissionDeniedException.class);
    }

    @Test
    void enforce_explicitContext_skipsFactory() {
        SecurityContext ctx = SecurityContext.user("bob", "acme", List.of("ops"));

        authority.enforce(ctx, new Resource.Tenant("acme"), Action.ADMIN);

        assertThat(recorder.lastCheck().subject()).isSameAs(ctx);
    }

    private static HttpServletRequest mockRequestWith(String username, String tenantId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> attrs = new HashMap<>();
        when(request.getAttribute("vance.access.username")).thenReturn(username);
        when(request.getAttribute("vance.access.tenantId")).thenReturn(tenantId);
        when(request.getAttribute(SecurityContextFactory.REQ_ATTR_CONTEXT))
                .thenAnswer(inv -> attrs.get(SecurityContextFactory.REQ_ATTR_CONTEXT));
        org.mockito.Mockito.doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(
                org.mockito.ArgumentMatchers.eq(SecurityContextFactory.REQ_ATTR_CONTEXT),
                org.mockito.ArgumentMatchers.any());
        return request;
    }
}
