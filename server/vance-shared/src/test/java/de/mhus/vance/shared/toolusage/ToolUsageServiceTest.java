package de.mhus.vance.shared.toolusage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The counter store behind the tool-surface budget's tie-break. What
 * matters here is that it never gets in the way: a write that cannot be
 * attributed is dropped rather than mis-filed, a Mongo failure is a lost
 * ranking hint and not a failed tool call, and the read memo cannot grow
 * without bound on a long-lived pod.
 */
class ToolUsageServiceTest {

    private MongoTemplate mongo;
    private ToolUsageRepository repository;
    private ToolUsageService service;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        repository = mock(ToolUsageRepository.class);
        service = new ToolUsageService(mongo, repository);
    }

    @Test
    void incompleteScope_isNotWritten() {
        // Without a tenant or project the row could not be read back by any
        // consumer, so writing it would only produce orphans.
        service.recordCall(null, "proj", "arthur", "doc_read", "doc");
        service.recordCall("acme", " ", "arthur", "doc_read", "doc");
        service.recordCall("acme", "proj", "arthur", null, "doc");

        verifyNoInteractions(mongo);
    }

    @Test
    void missingRole_landsInTheUnknownBucket() {
        service.recordCall("acme", "proj", null, "doc_read", "doc");

        verify(mongo).upsert(
                argThatQueryHasRecipe(ToolUsageService.ROLE_UNKNOWN), any(), any(Class.class));
    }

    @Test
    void mongoFailure_doesNotPropagate() {
        // A counter is a ranking hint; losing one must never fail the tool
        // call that produced it.
        when(mongo.upsert(any(), any(), any(Class.class)))
                .thenThrow(new IllegalStateException("mongo down"));

        service.recordCall("acme", "proj", "arthur", "doc_read", "doc");
        service.recordDiscovery("acme", "proj", "arthur", "doc_read", "doc");
    }

    @Test
    void demandCombinesCallsAndDiscoveryHits() {
        when(repository.findByTenantIdAndProjectIdAndRecipeName("acme", "proj", "arthur"))
                .thenReturn(List.of(doc("doc_read", 5, 2), doc("file_write", 0, 3)));

        assertThat(service.demandByTool("acme", "proj", "arthur"))
                .containsEntry("doc_read", 7L)
                .containsEntry("file_write", 3L);
    }

    @Test
    void demandIsMemoised_untilInvalidated() {
        when(repository.findByTenantIdAndProjectIdAndRecipeName("acme", "proj", "arthur"))
                .thenReturn(List.of(doc("doc_read", 5, 2)));

        service.demandByTool("acme", "proj", "arthur");
        service.demandByTool("acme", "proj", "arthur");
        verify(repository).findByTenantIdAndProjectIdAndRecipeName("acme", "proj", "arthur");

        service.invalidateCache();
        service.demandByTool("acme", "proj", "arthur");
        verify(repository, org.mockito.Mockito.times(2))
                .findByTenantIdAndProjectIdAndRecipeName("acme", "proj", "arthur");
    }

    @Test
    void readFailure_degradesToNoSignal() {
        when(repository.findByTenantIdAndProjectIdAndRecipeName(any(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        assertThat(service.demandByTool("acme", "proj", "arthur")).isEmpty();
    }

    @Test
    void blankScope_skipsTheReadEntirely() {
        assertThat(service.demandByTool(null, "proj", "arthur")).isEmpty();
        assertThat(service.demandByTool("acme", " ", "arthur")).isEmpty();

        verifyNoInteractions(repository);
    }

    @Test
    void readMemoIsBounded_soAPodCannotAccumulateSnapshotsForever() {
        // One entry per (tenant, project, role). Nothing ever removes an
        // expired one, so without the LRU cap a busy multi-tenant pod would
        // hold every combination it ever saw for its whole lifetime.
        when(repository.findByTenantIdAndProjectIdAndRecipeName(any(), any(), any()))
                .thenReturn(List.of(doc("doc_read", 1, 0)));
        int overflow = ToolUsageService.READ_CACHE_MAX + 10;
        for (int i = 0; i < overflow; i++) {
            service.demandByTool("acme", "proj-" + i, "arthur");
        }

        // The first project was evicted → its next read hits Mongo again,
        // while the most recent one is still served from the memo.
        service.demandByTool("acme", "proj-0", "arthur");
        verify(repository, org.mockito.Mockito.times(2))
                .findByTenantIdAndProjectIdAndRecipeName("acme", "proj-0", "arthur");

        service.demandByTool("acme", "proj-" + (overflow - 1), "arthur");
        verify(repository)
                .findByTenantIdAndProjectIdAndRecipeName(
                        "acme", "proj-" + (overflow - 1), "arthur");
    }

    @Test
    void listByProject_returnsEmptyOnFailureInsteadOfThrowing() {
        when(repository.findByTenantIdAndProjectId(any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        assertThat(service.listByProject("acme", "proj")).isEmpty();
        verify(repository, never()).findByTenantIdAndProjectIdAndRecipeName(any(), any(), any());
    }

    private static ToolUsageDocument doc(String tool, long calls, long discoveryHits) {
        ToolUsageDocument d = new ToolUsageDocument();
        d.setToolName(tool);
        d.setCalls(calls);
        d.setDiscoveryHits(discoveryHits);
        return d;
    }

    /** Matches an upsert whose query filters on the given {@code recipeName}. */
    private static org.springframework.data.mongodb.core.query.Query argThatQueryHasRecipe(
            String recipe) {
        return org.mockito.ArgumentMatchers.argThat(q ->
                q != null && q.getQueryObject().get("recipeName") != null
                        && recipe.equals(q.getQueryObject().get("recipeName").toString()));
    }
}
