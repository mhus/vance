package de.mhus.vance.simpleauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * CRUD, upsert idempotency and cache invalidation for
 * {@link PermissionGrantService} (permission-system Phase B).
 */
class PermissionGrantServiceTest {

    private final PermissionGrantRepository repo = mock(PermissionGrantRepository.class);
    private final PermissionGrantService service = new PermissionGrantService(repo);

    @Test
    void forScope_caches_and_a_second_call_hits_the_cache() {
        when(repo.findByTenantIdAndScopeTypeAndScopeId("acme", GrantScopeType.PROJECT, "proj"))
                .thenReturn(List.of(grant("alice", GrantRole.WRITER)));

        service.forScope("acme", GrantScopeType.PROJECT, "proj");
        service.forScope("acme", GrantScopeType.PROJECT, "proj");

        verify(repo, times(1))
                .findByTenantIdAndScopeTypeAndScopeId("acme", GrantScopeType.PROJECT, "proj");
    }

    @Test
    void set_new_grant_persists_with_createdBy_and_invalidates_cache() {
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByTenantIdAndScopeTypeAndScopeId("acme", GrantScopeType.PROJECT, "proj"))
                .thenReturn(List.of());
        // warm the cache
        service.forScope("acme", GrantScopeType.PROJECT, "proj");

        PermissionGrantDocument saved = service.set("acme", GrantScopeType.PROJECT, "proj",
                GrantSubjectType.USER, "alice", GrantRole.WRITER, "root");

        assertThat(saved.getRole()).isEqualTo(GrantRole.WRITER);
        assertThat(saved.getCreatedBy()).isEqualTo("root");
        // cache was invalidated → next forScope re-queries (2 repo calls total)
        service.forScope("acme", GrantScopeType.PROJECT, "proj");
        verify(repo, times(2))
                .findByTenantIdAndScopeTypeAndScopeId("acme", GrantScopeType.PROJECT, "proj");
    }

    @Test
    void set_existing_grant_overwrites_role_keeps_createdBy() {
        PermissionGrantDocument existing = grant("alice", GrantRole.READER);
        existing.setId("g1");
        existing.setCreatedBy("original-admin");
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                eq("acme"), eq(GrantScopeType.PROJECT), eq("proj"),
                eq(GrantSubjectType.USER), eq("alice"))).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PermissionGrantDocument saved = service.set("acme", GrantScopeType.PROJECT, "proj",
                GrantSubjectType.USER, "alice", GrantRole.ADMIN, "someone-else");

        assertThat(saved.getRole()).isEqualTo(GrantRole.ADMIN);       // overwritten
        assertThat(saved.getCreatedBy()).isEqualTo("original-admin"); // preserved
    }

    @Test
    void remove_absent_grant_is_noop() {
        when(repo.findByTenantIdAndScopeTypeAndScopeIdAndSubjectTypeAndSubjectId(
                any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        boolean removed = service.remove("acme", GrantScopeType.PROJECT, "proj",
                GrantSubjectType.USER, "ghost");

        assertThat(removed).isFalse();
        verify(repo, times(0)).delete(any());
    }

    private static PermissionGrantDocument grant(String user, GrantRole role) {
        return PermissionGrantDocument.builder()
                .tenantId("acme").scopeType(GrantScopeType.PROJECT).scopeId("proj")
                .subjectType(GrantSubjectType.USER).subjectId(user).role(role).build();
    }
}
