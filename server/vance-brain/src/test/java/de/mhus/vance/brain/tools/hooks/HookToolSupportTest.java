package de.mhus.vance.brain.tools.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Security regression (code-review-2 S4): the hook/scheduler/event *_set tools
 * write reserved {@code _vance/…} YAML that can carry {@code runAs} authority.
 * {@code _vance/} is server-owned (SYSTEM-only at the document chokepoint), so
 * as the dedicated authoring tool this support owns the policy: it enforces
 * project-ADMIN itself and then writes as a trusted SYSTEM operation with the
 * caller's real subject kept for audit. This pins the shared actor-construction
 * helper (identical code in the scheduler/event supports).
 */
class HookToolSupportTest {

    private final MegadodoService megadodo = mock(MegadodoService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final HookToolSupport support =
            new HookToolSupport(megadodo, contextFactory, permissionService);

    @Test
    void adminSystemActor_projectAdmin_returnsSystemReasonWithRealSubject() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of("team-a"));
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);
        // permissionService.enforce is a no-op mock → ADMIN granted.

        WriteActor actor = support.adminSystemActor("acme", "proj", "alice");

        assertThat(actor.reason()).isEqualTo(WriteReason.SYSTEM);
        assertThat(actor.subject()).isEqualTo(subject);
    }

    @Test
    void adminSystemActor_nonAdmin_throws_beforeAnyWrite() {
        SecurityContext subject = SecurityContext.user("bob", "acme", List.of());
        when(contextFactory.forToolSubject("acme", "bob")).thenReturn(subject);
        doThrow(new PermissionDeniedException(subject, new Resource.Project("acme", "proj"), Action.ADMIN))
                .when(permissionService).enforce(
                        eq(subject), eq(new Resource.Project("acme", "proj")), eq(Action.ADMIN));

        assertThatThrownBy(() -> support.adminSystemActor("acme", "proj", "bob"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void adminSystemActor_headlessRun_mapsToSystemSubject_passesAsInternalActor() {
        // A headless/scheduler-triggered run has no userId → the factory maps it
        // to SecurityContext.SYSTEM, which the framework trusts (R1) → the ADMIN
        // enforce passes and the write proceeds as an internal operation.
        when(contextFactory.forToolSubject("acme", null)).thenReturn(SecurityContext.SYSTEM);

        WriteActor actor = support.adminSystemActor("acme", "proj", null);

        assertThat(actor.reason()).isEqualTo(WriteReason.SYSTEM);
        assertThat(actor.subject()).isEqualTo(SecurityContext.SYSTEM);
    }
}
