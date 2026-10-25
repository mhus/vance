package de.mhus.vance.brain.tools.eddie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.eddie.activity.EddieActivityService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The administering tools act only as a real user: a headless call would
 * otherwise run as SYSTEM, which passes every check.
 */
class AdminToolSupportTest {

    private EddieContext eddieContext;
    private SecurityContextFactory contextFactory;
    private AdminToolSupport support;

    @BeforeEach
    void setUp() {
        eddieContext = mock(EddieContext.class);
        contextFactory = mock(SecurityContextFactory.class);
        support = new AdminToolSupport(
                eddieContext, mock(EddieActivityService.class), mock(PermissionService.class), contextFactory);
    }

    @Test
    void headlessCall_isRefusedInsteadOfRunningAsSystem() {
        ToolInvocationContext headless = new ToolInvocationContext("acme", "p", "s", "proc", null);

        assertThatThrownBy(() -> support.actor(headless, "account_create"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("requires a user identity");
        verify(contextFactory, never()).forToolSubject(anyString(), any());
    }

    @Test
    void pinnedProcess_isRefused() {
        ToolInvocationContext ctx = new ToolInvocationContext("acme", "p", "s", "proc", "alice");
        when(eddieContext.isPinnedToOwnProject(ctx)).thenReturn(true);

        assertThatThrownBy(() -> support.actor(ctx, "team_delete"))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not allowed in this process");
    }

    @Test
    void user_isTheSubject() {
        ToolInvocationContext ctx = new ToolInvocationContext("acme", "p", "s", "proc", "alice");
        SecurityContext alice = SecurityContext.user("alice", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(alice);

        assertThat(support.actor(ctx, "account_list")).isSameAs(alice);
    }

    @Test
    void strings_acceptsAListOrASingleValue() {
        assertThat(AdminToolSupport.strings(Map.of("m", List.of("a", " ", "b ")), "m"))
                .containsExactly("a", "b");
        assertThat(AdminToolSupport.strings(Map.of("m", "a"), "m")).containsExactly("a");
        assertThat(AdminToolSupport.strings(Map.of(), "m")).isEmpty();
    }
}
