package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The security property of an attenuated credential: it can only ever take
 * rights away.
 *
 * <p>Every test here is written from the resolver saying <em>yes</em>, because
 * that is the only interesting direction — a restriction that denies what was
 * already denied proves nothing.
 */
class PermissionServiceConfinementTest {

    private final PermissionResolver resolver = mock(PermissionResolver.class);
    private final PermissionService service = new PermissionService(List.of(resolver));

    private final SecurityContext confined =
            SecurityContext.restrictedUser("alice", "acme", List.of(), "links-proj");

    @Test
    void confinedContext_allowsResourceInsideItsProject() {
        when(resolver.isAllowed(any(), any(), any())).thenReturn(true);

        assertThat(service.check(confined,
                new Resource.Project("acme", "links-proj"), Action.WRITE)).isTrue();
    }

    @Test
    void confinedContext_deniesOtherProject_evenWhenResolverAllows() {
        when(resolver.isAllowed(any(), any(), any())).thenReturn(true);

        assertThat(service.check(confined,
                new Resource.Project("acme", "payroll"), Action.READ)).isFalse();
    }

    @Test
    void confinedContext_deniesDocumentInOtherProject() {
        when(resolver.isAllowed(any(), any(), any())).thenReturn(true);

        assertThat(service.check(confined,
                new Resource.Document("acme", "payroll", "salaries.md"), Action.READ)).isFalse();
    }

    /**
     * The confinement is checked before the resolver, so a provider never even
     * sees an out-of-scope question. That ordering is what keeps an enterprise
     * resolver from being able to widen a confined context by accident.
     */
    @Test
    void confinedContext_doesNotConsultResolver_whenOutOfScope() {
        service.check(confined, new Resource.Project("acme", "payroll"), Action.READ);

        verify(resolver, never()).isAllowed(any(), any(), any());
    }

    /**
     * A resource that names no project is refused. Fail-closed is the whole
     * point: "we cannot classify this" must not read as "go ahead", or every
     * new Resource kind would quietly widen every token already in the field.
     */
    @Test
    void confinedContext_deniesResourcesWithoutAProject() {
        when(resolver.isAllowed(any(), any(), any())).thenReturn(true);

        assertThat(service.check(confined, new Resource.Tenant("acme"), Action.READ)).isFalse();
        assertThat(service.check(confined,
                new Resource.User("acme", "alice"), Action.READ)).isFalse();
        assertThat(service.check(confined,
                new Resource.Team("acme", "eng"), Action.READ)).isFalse();
        assertThat(service.check(confined,
                new Resource.InboxItem("acme", "item-1", "alice"), Action.READ)).isFalse();
    }

    @Test
    void confinedContext_allowsProjectScopedSetting_butNotTenantScopedOne() {
        when(resolver.isAllowed(any(), any(), any())).thenReturn(true);

        assertThat(service.check(confined,
                new Resource.Setting("acme", "project", "links-proj", "a.b"),
                Action.READ)).isTrue();
        assertThat(service.check(confined,
                new Resource.Setting("acme", "tenant", "acme", "a.b"),
                Action.READ)).isFalse();
        assertThat(service.check(confined,
                new Resource.Setting("acme", "project", "payroll", "a.b"),
                Action.READ)).isFalse();
    }

    /**
     * The one that would be easy to get wrong: {@code WriteReason.SYSTEM} means
     * "server code vouches that this write is legitimate", not "this caller may
     * reach another project". Scope is answered before policy, so the
     * system-trust short-circuit cannot carry a confined credential out of its
     * project.
     */
    @Test
    void confinedContext_isNotEscapedBySystemWriteReason() {
        assertThat(service.check(confined,
                new Resource.Document("acme", "payroll", "_vance/x.yaml"),
                Action.WRITE, WriteReason.SYSTEM)).isFalse();
    }

    @Test
    void confinedContext_stillHonoursSystemWriteReason_insideItsProject() {
        assertThat(service.check(confined,
                new Resource.Document("acme", "links-proj", "_vance/x.yaml"),
                Action.WRITE, WriteReason.SYSTEM)).isTrue();
    }

    @Test
    void unconfinedContext_isUnaffected() {
        when(resolver.isAllowed(any(), any(), any())).thenReturn(true);
        SecurityContext plain = SecurityContext.user("alice", "acme", List.of());

        assertThat(service.check(plain, new Resource.Tenant("acme"), Action.READ)).isTrue();
        assertThat(service.check(plain,
                new Resource.Project("acme", "anything"), Action.WRITE)).isTrue();
    }
}
