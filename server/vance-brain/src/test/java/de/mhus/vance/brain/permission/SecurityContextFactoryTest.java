package de.mhus.vance.brain.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.ws.ConnectionContext;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.SubjectType;
import de.mhus.vance.shared.team.TeamDocument;
import de.mhus.vance.shared.team.TeamService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecurityContextFactoryTest {

    private TeamService teamService;
    private SecurityContextFactory factory;

    @BeforeEach
    void setUp() {
        teamService = mock(TeamService.class);
        factory = new SecurityContextFactory(teamService);
    }

    @Test
    void fromRequest_buildsUserContext_andResolvesTeams() {
        HttpServletRequest request = mockRequestWith("alice", "acme");
        when(teamService.byMember("acme", "alice"))
                .thenReturn(List.of(team("admins"), team("dev")));

        SecurityContext ctx = factory.fromRequest(request);

        assertThat(ctx.subjectType()).isEqualTo(SubjectType.USER);
        assertThat(ctx.subjectId()).isEqualTo("alice");
        assertThat(ctx.tenantId()).isEqualTo("acme");
        assertThat(ctx.teams()).containsExactly("admins", "dev");
    }

    @Test
    void fromRequest_cachesContextOnRequest_acrossCalls() {
        HttpServletRequest request = mockRequestWith("alice", "acme");
        when(teamService.byMember("acme", "alice")).thenReturn(List.of());

        SecurityContext first = factory.fromRequest(request);
        SecurityContext second = factory.fromRequest(request);
        SecurityContext third = factory.fromRequest(request);

        assertThat(first).isSameAs(second).isSameAs(third);
        // Cached, so we never query teams again after the first call.
        verify(teamService, times(1)).byMember("acme", "alice");
    }

    @Test
    void fromRequest_throws_whenAccessFilterDidNotRun() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        // No ATTR_USERNAME / ATTR_TENANT_ID — simulates a route that
        // bypassed BrainAccessFilter (misconfiguration).
        assertThatThrownBy(() -> factory.fromRequest(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BrainAccessFilter");
    }

    @Test
    void fromConnection_buildsUserContext_fromWsState() {
        ConnectionContext connection = new ConnectionContext(
                "acme", "alice", "Alice A.",
                "default", "1.0", "vance-foot",
                "conn-1", "10.0.0.1");
        when(teamService.byMember("acme", "alice")).thenReturn(List.of(team("dev")));

        SecurityContext ctx = factory.fromConnection(connection);

        assertThat(ctx.subjectType()).isEqualTo(SubjectType.USER);
        assertThat(ctx.subjectId()).isEqualTo("alice");
        assertThat(ctx.tenantId()).isEqualTo("acme");
        assertThat(ctx.teams()).containsExactly("dev");
    }

    private static HttpServletRequest mockRequestWith(String username, String tenantId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(AccessFilterBase.ATTR_USERNAME, username);
        attrs.put(AccessFilterBase.ATTR_TENANT_ID, tenantId);
        when(request.getAttribute(AccessFilterBase.ATTR_USERNAME)).thenReturn(username);
        when(request.getAttribute(AccessFilterBase.ATTR_TENANT_ID)).thenReturn(tenantId);
        // Simulate getAttribute / setAttribute via the local map for the
        // permission-context cache key.
        when(request.getAttribute(SecurityContextFactory.REQ_ATTR_CONTEXT))
                .thenAnswer(inv -> attrs.get(SecurityContextFactory.REQ_ATTR_CONTEXT));
        org.mockito.stubbing.Answer<Void> setter = inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        };
        org.mockito.Mockito.doAnswer(setter)
                .when(request).setAttribute(org.mockito.ArgumentMatchers.eq(SecurityContextFactory.REQ_ATTR_CONTEXT),
                        org.mockito.ArgumentMatchers.any());
        return request;
    }

    private static TeamDocument team(String name) {
        TeamDocument t = new TeamDocument();
        t.setName(name);
        return t;
    }
}
