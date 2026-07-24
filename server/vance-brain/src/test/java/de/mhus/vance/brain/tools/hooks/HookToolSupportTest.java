package de.mhus.vance.brain.tools.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.permission.WriteReason;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Security regression (code-review-2 B4): the hook/scheduler/event *_set tools
 * write reserved {@code _vance/…} YAML that can carry {@code runAs} authority.
 * The write actor they build must be a user-driven write (WriteReason.USER)
 * carrying the caller's real subject — never WriteActor.SYSTEM, which would
 * fail-open past the reserved-prefix ADMIN gate (R4). This pins the shared
 * actor-construction helper (identical code in the scheduler/event supports).
 */
class HookToolSupportTest {

    private final EventLogService eventLog = mock(EventLogService.class);
    private final SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
    private final HookToolSupport support = new HookToolSupport(eventLog, contextFactory);

    @Test
    void writeActor_isUserReason_withResolvedSubject_notSystemBypass() {
        SecurityContext subject = SecurityContext.user("alice", "acme", List.of("team-a"));
        when(contextFactory.forToolSubject("acme", "alice")).thenReturn(subject);

        WriteActor actor = support.writeActor("acme", "alice");

        assertThat(actor.reason()).isEqualTo(WriteReason.USER);
        assertThat(actor.subject()).isEqualTo(subject);
        assertThat(actor.subject()).isNotEqualTo(SecurityContext.SYSTEM);
    }

    @Test
    void writeActor_headlessRun_mapsToSystemSubject_stillUserReason() {
        // A headless/scheduler-triggered run has no userId → the factory maps it
        // to SecurityContext.SYSTEM, which the resolver trusts (R1). The reason
        // stays USER; genuine system runs still pass without a blanket bypass.
        when(contextFactory.forToolSubject("acme", null)).thenReturn(SecurityContext.SYSTEM);

        WriteActor actor = support.writeActor("acme", null);

        assertThat(actor.reason()).isEqualTo(WriteReason.USER);
        assertThat(actor.subject()).isEqualTo(SecurityContext.SYSTEM);
    }
}
