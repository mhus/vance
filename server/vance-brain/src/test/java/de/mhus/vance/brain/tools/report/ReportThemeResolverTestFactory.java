package de.mhus.vance.brain.tools.report;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.shared.document.DocumentRefResolver;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.PermissionService;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Test helper: builds a {@link ReportThemeResolver} whose default-theme
 * layer reads the real bundled {@code default.css} from the test classpath
 * (so render output stays byte-stable), while the cascade and css-ref
 * layers back onto Mockito mocks. Tests that don't set {@code theme:} /
 * {@code css:} never touch those mocks.
 */
final class ReportThemeResolverTestFactory {

    private ReportThemeResolverTestFactory() {}

    /** Permits every check — for fixtures whose subject is not the point. */
    static PermissionService permissive() {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.check(any(), any(), any())).thenReturn(true);
        return permissions;
    }

    static ReportThemeResolver withDefaultClasspath(
            DocumentService documentService,
            DocumentRefResolver documentRefResolver) {
        return withDefaultClasspath(documentService, documentRefResolver, permissive());
    }

    static ReportThemeResolver withDefaultClasspath(
            DocumentService documentService,
            DocumentRefResolver documentRefResolver,
            PermissionService permissionService) {
        return new ReportThemeResolver(
                documentService,
                documentRefResolver,
                new PathMatchingResourcePatternResolver(),
                permissionService);
    }
}
