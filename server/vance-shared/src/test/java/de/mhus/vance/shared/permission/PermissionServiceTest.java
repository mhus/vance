package de.mhus.vance.shared.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionServiceTest {

    private final PermissionResolver resolver = mock(PermissionResolver.class);
    private final PermissionService service = new PermissionService(List.of(resolver));

    private final SecurityContext alice = SecurityContext.user("alice", "acme", List.of());
    private final Resource resource = new Resource.Project("acme", "proj");

    @Test
    void check_delegatesToResolver_andPropagatesResult() {
        when(resolver.isAllowed(alice, resource, Action.READ, WriteReason.USER)).thenReturn(true);
        when(resolver.isAllowed(alice, resource, Action.WRITE, WriteReason.USER)).thenReturn(false);

        assertThat(service.check(alice, resource, Action.READ)).isTrue();
        assertThat(service.check(alice, resource, Action.WRITE)).isFalse();

        verify(resolver).isAllowed(alice, resource, Action.READ, WriteReason.USER);
        verify(resolver).isAllowed(alice, resource, Action.WRITE, WriteReason.USER);
    }

    @Test
    void enforce_passesThrough_whenResolverAllows() {
        when(resolver.isAllowed(any(), any(), any(), any())).thenReturn(true);

        // Must not throw.
        service.enforce(alice, resource, Action.READ);
    }

    @Test
    void enforce_throws_whenResolverDenies() {
        when(resolver.isAllowed(alice, resource, Action.WRITE)).thenReturn(false);

        assertThatThrownBy(() -> service.enforce(alice, resource, Action.WRITE))
                .isInstanceOf(PermissionDeniedException.class)
                .satisfies(t -> {
                    PermissionDeniedException ex = (PermissionDeniedException) t;
                    assertThat(ex.getSubject()).isSameAs(alice);
                    assertThat(ex.getResource()).isSameAs(resource);
                    assertThat(ex.getAction()).isEqualTo(Action.WRITE);
                });
    }

    @Test
    void enforce_consultsResolverExactlyOnce() {
        when(resolver.isAllowed(any(), any(), any(), any())).thenReturn(true);

        service.enforce(alice, resource, Action.READ);

        verify(resolver).isAllowed(alice, resource, Action.READ, WriteReason.USER);
        verify(resolver, never()).isAllowed(alice, resource, Action.WRITE, WriteReason.USER);
    }

    @Test
    void construction_failsFast_whenNoProviderPresent() {
        assertThatThrownBy(() -> new PermissionService(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one permission provider")
                .hasMessageContaining("found 0");
    }

    @Test
    void construction_failsFast_whenMultipleProvidersPresent() {
        assertThatThrownBy(() ->
                new PermissionService(List.of(mock(PermissionResolver.class),
                        mock(PermissionResolver.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one permission provider")
                .hasMessageContaining("found 2");
    }
}
