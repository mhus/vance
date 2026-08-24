package de.mhus.vance.brain.tools.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.inbox.MaximegalonStatus;
import de.mhus.vance.api.inbox.MaximegalonType;
import de.mhus.vance.brain.inbox.InboxAuthz;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.inbox.MaximegalonDocument;
import de.mhus.vance.shared.inbox.MaximegalonService;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.SecurityContext;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Archiving is the only settling act in either family, so its guard is the one
 * that matters: an open ask must not vanish off a desk while a process waits on
 * the decision behind it.
 */
class InboxArchiveToolTest {

    private static final String TENANT = "acme";
    private static final String OWNER = "wile.coyote";

    private MaximegalonService threads;
    private InboxAuthz authz;
    private PermissionService permissionService;
    private InboxArchiveTool tool;

    @BeforeEach
    void setUp() {
        threads = mock(MaximegalonService.class);
        authz = mock(InboxAuthz.class);
        permissionService = mock(PermissionService.class);
        SecurityContextFactory contextFactory = mock(SecurityContextFactory.class);
        when(contextFactory.forToolSubject(any(), any()))
                .thenReturn(SecurityContext.user(OWNER, TENANT, java.util.List.of()));
        when(permissionService.check(any(), any(), any())).thenReturn(true);
        tool = new InboxArchiveTool(threads,
                new InboxToolSupport(threads, permissionService, contextFactory, authz));
    }

    private static ToolInvocationContext ctx() {
        ToolInvocationContext c = mock(ToolInvocationContext.class);
        when(c.tenantId()).thenReturn(TENANT);
        when(c.userId()).thenReturn(OWNER);
        return c;
    }

    private MaximegalonDocument given(MaximegalonStatus status, boolean requiresAction) {
        MaximegalonDocument doc = MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT)
                .type(MaximegalonType.APPROVAL)
                .status(status)
                .assignedToUserId(OWNER)
                .requiresAction(requiresAction)
                .build();
        when(threads.findById(TENANT, "t1")).thenReturn(Optional.of(doc));
        when(authz.mayDecide(TENANT, OWNER, OWNER)).thenReturn(true);
        return doc;
    }

    @Test
    void archive_openAsk_isRefusedAndSaysWhatToDoInstead() {
        given(MaximegalonStatus.PENDING, true);

        Map<String, Object> out = tool.invoke(Map.of("threadIds", java.util.List.of("t1")), ctx());

        assertThat((java.util.List<?>) out.get("archived")).isEmpty();
        assertThat(out.get("skipped").toString())
                .contains("open request").contains("add a contribution");
        verify(threads, never()).archive(any(), any(), any());
    }

    @Test
    void archive_settledThread_goesThrough() {
        MaximegalonDocument doc = given(MaximegalonStatus.ANSWERED, true);
        MaximegalonDocument archived = MaximegalonDocument.builder()
                .id("t1").tenantId(TENANT).status(MaximegalonStatus.ARCHIVED).build();
        when(threads.archive(TENANT, "t1", OWNER)).thenReturn(Optional.of(archived));

        Map<String, Object> out = tool.invoke(Map.of("threadIds", java.util.List.of("t1")), ctx());

        assertThat(out.get("archived")).isEqualTo(java.util.List.of("t1"));
        assertThat((java.util.List<?>) out.get("skipped")).isEmpty();
        assertThat(doc.getStatus()).isEqualTo(MaximegalonStatus.ANSWERED); // service owns the write
    }

    @Test
    void archive_pendingOutput_isNotAnAskAndMayBeCleared() {
        // requiresAction is the discriminator, not status: an OUTPUT_* item sits
        // PENDING until read and blocks nobody.
        given(MaximegalonStatus.PENDING, false);
        when(threads.archive(TENANT, "t1", OWNER)).thenReturn(Optional.of(
                MaximegalonDocument.builder().id("t1").status(MaximegalonStatus.ARCHIVED).build()));

        assertThat(tool.invoke(Map.of("threadIds", java.util.List.of("t1")), ctx()).get("archived"))
                .isEqualTo(java.util.List.of("t1"));
    }

    @Test
    void archive_readerWhoMayNotDecide_isToldPreciselyWhy() {
        given(MaximegalonStatus.ANSWERED, false);
        when(authz.mayDecide(TENANT, OWNER, OWNER)).thenReturn(false);

        Map<String, Object> out = tool.invoke(Map.of("threadIds", java.util.List.of("t1")), ctx());

        assertThat((java.util.List<?>) out.get("archived")).isEmpty();
        assertThat(out.get("skipped").toString())
                .contains("may read").contains("not settle it");
    }

    @Test
    void archive_alreadyArchived_isAStatementNotAFailure() {
        given(MaximegalonStatus.ARCHIVED, false);

        Map<String, Object> out = tool.invoke(Map.of("threadIds", java.util.List.of("t1")), ctx());

        // Idempotent: counting it as archived keeps a re-run from reading as a
        // partial failure.
        assertThat(out.get("archived")).isEqualTo(java.util.List.of("t1"));
        verify(threads, never()).archive(any(), any(), any());
    }
}
