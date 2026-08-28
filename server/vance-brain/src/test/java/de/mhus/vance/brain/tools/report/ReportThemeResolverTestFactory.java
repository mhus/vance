package de.mhus.vance.brain.tools.report;

import de.mhus.vance.shared.document.DocumentRefResolver;
import de.mhus.vance.shared.document.DocumentService;
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

    static ReportThemeResolver withDefaultClasspath(
            DocumentService documentService,
            DocumentRefResolver documentRefResolver) {
        return new ReportThemeResolver(
                documentService,
                documentRefResolver,
                new PathMatchingResourcePatternResolver());
    }
}
