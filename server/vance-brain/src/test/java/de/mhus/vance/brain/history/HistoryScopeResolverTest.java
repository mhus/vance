package de.mhus.vance.brain.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link HistoryScopeResolver}: scope strings translate to the right
 * {@code Set<String>} of process ids — process / children / session.
 * Tenant pinning happens inside {@link ThinkProcessService}; we just
 * mock the service to return the expected set.
 */
class HistoryScopeResolverTest {

    private final ThinkProcessService thinkProcessService = mock(ThinkProcessService.class);
    private final ToolInvocationContext ctx = new ToolInvocationContext(
            "tenant-1", "proj", "sess", "process-abc", "user");

    @Test
    void process_returnsCallerOnly_andDoesNotTouchService() {
        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_PROCESS, ctx, thinkProcessService);

        assertThat(ids).containsExactly("process-abc");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void children_resolvesViaFindDescendantIds() {
        when(thinkProcessService.findDescendantIds("process-abc"))
                .thenReturn(Set.of("process-abc", "c1", "c2"));

        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_CHILDREN, ctx, thinkProcessService);

        assertThat(ids).containsExactlyInAnyOrder("process-abc", "c1", "c2");
    }

    @Test
    void children_emptyResult_fallsBackToCaller() {
        // Race: process row vanished between request and lookup. Don't
        // collapse the search to "no rows" — keep the caller's own
        // history reachable.
        when(thinkProcessService.findDescendantIds("process-abc"))
                .thenReturn(Set.of());

        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_CHILDREN, ctx, thinkProcessService);

        assertThat(ids).containsExactly("process-abc");
    }

    @Test
    void session_resolvesViaFindBySession() {
        when(thinkProcessService.findBySession("tenant-1", "sess"))
                .thenReturn(List.of(
                        ThinkProcessDocument.builder().id("process-abc").build(),
                        ThinkProcessDocument.builder().id("sib-1").build()));

        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_SESSION, ctx, thinkProcessService);

        assertThat(ids).containsExactlyInAnyOrder("process-abc", "sib-1");
    }

    @Test
    void session_alwaysIncludesCaller_evenIfMissingFromLookup() {
        // Mongo race: caller's row disappeared between session-list and
        // resolve. Caller still gets its own process back.
        when(thinkProcessService.findBySession("tenant-1", "sess"))
                .thenReturn(List.of());

        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_SESSION, ctx, thinkProcessService);

        assertThat(ids).containsExactly("process-abc");
    }

    @Test
    void session_withoutSessionId_fallsBackToCaller() {
        ToolInvocationContext noSession =
                new ToolInvocationContext("t", "p", null, "process-abc", "u");

        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_SESSION, noSession, thinkProcessService);

        assertThat(ids).containsExactly("process-abc");
        verifyNoInteractions(thinkProcessService);
    }

    @Test
    void blankProcessId_returnsEmpty() {
        ToolInvocationContext noProcess =
                new ToolInvocationContext("t", "p", "s", null, "u");

        Set<String> ids = HistoryScopeResolver.resolve(
                HistorySearchTool.SCOPE_PROCESS, noProcess, thinkProcessService);

        assertThat(ids).isEmpty();
    }

    @Test
    void unknownScope_throws() {
        assertThatThrownBy(() ->
                HistoryScopeResolver.resolve("everything", ctx, thinkProcessService))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("everything");
    }
}
