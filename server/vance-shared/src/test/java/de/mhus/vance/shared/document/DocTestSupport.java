package de.mhus.vance.shared.document;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.permission.PermissionService;
import org.springframework.beans.factory.ObjectProvider;

/** Shared test wiring for {@link DocumentService}. */
final class DocTestSupport {

    private DocTestSupport() {}

    /**
     * An {@link ObjectProvider} yielding a mock {@link PermissionService}
     * whose {@code enforce} is a no-op — so the write authz gate passes and
     * these tests exercise the orthogonal behaviour (soft-lock, summary,
     * archive, notes, streaming), not authorization.
     */
    @SuppressWarnings("unchecked")
    static ObjectProvider<PermissionService> permissionProvider() {
        ObjectProvider<PermissionService> psp = mock(ObjectProvider.class);
        when(psp.getObject()).thenReturn(mock(PermissionService.class));
        return psp;
    }
}
