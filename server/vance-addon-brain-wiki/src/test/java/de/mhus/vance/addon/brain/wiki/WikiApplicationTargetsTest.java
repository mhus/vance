package de.mhus.vance.addon.brain.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.applications.VanceApplication.AppTarget;
import de.mhus.vance.brain.applications.VanceApplication.TargetPurpose;
import de.mhus.vance.brain.applications.VanceApplication.TargetsContext;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wiki as a link destination: which of its pages a link can point at.
 *
 * <p>The interesting case is the unusable one. {@code slugify} can return the
 * empty string — {@code humanise} has a branch for exactly that — and
 * {@link AppTarget} rejects a blank handle by throwing. Both callers of
 * {@code targets} read an exception as "this app has no places", which is a
 * normal answer and therefore does not look like a failure, so an unguarded
 * loop would let one badly named file make the whole wiki unlinkable.
 */
class WikiApplicationTargetsTest {

    private final WikiFolderReader folderReader = mock(WikiFolderReader.class);
    private final WikiApplication app = new WikiApplication(
            folderReader, mock(WikiService.class), mock(WikiIndexRenderer.class),
            mock(WikiBacklinksRenderer.class), mock(DocumentService.class),
            mock(DocumentLinkBuilder.class), mock(SecurityContextFactory.class));

    private static WikiPage page(String space, String slug) {
        return new WikiPage(null, (space.isBlank() ? "" : space + "/") + slug + ".md",
                space, slug, WikiFolderReader.humanise(slug), /*main*/ false, List.of());
    }

    private void given(WikiPage... pages) {
        when(folderReader.scan(any(), any(), any()))
                .thenReturn(new WikiFolderReader.Scan("wiki", null, null, List.of(),
                        List.of(pages)));
    }

    private List<AppTarget> targets(TargetPurpose purpose) {
        return app.targets(new TargetsContext(
                "acme", "research", "wiki", "mara", purpose, Map.of()));
    }

    @Test
    void targets_navigate_handleIsTheSpaceQualifiedSlug() {
        given(page("", "main"), page("ops", "deploys"));

        assertThat(targets(TargetPurpose.NAVIGATE))
                .extracting(AppTarget::handle)
                .containsExactly("main", "ops/deploys");
    }

    @Test
    void targets_navigate_groupIsTheSpace() {
        given(page("", "main"), page("ops", "deploys"));

        assertThat(targets(TargetPurpose.NAVIGATE))
                .extracting(AppTarget::group)
                .containsExactly(null, "ops");
    }

    @Test
    void targets_pageWithoutASlug_isSkippedAndTheOthersSurvive() {
        // A file whose stem slugifies to nothing (`###.md`). Before the guard
        // this threw out of the loop and the wiki reported no places at all.
        given(page("", "main"), page("", ""), page("ops", "deploys"));

        assertThat(targets(TargetPurpose.NAVIGATE))
                .extracting(AppTarget::handle)
                .containsExactly("main", "ops/deploys");
    }

    @Test
    void targets_intake_offersNothing() {
        // A wiki has no inbox: a share must not invent a page in it.
        given(page("", "main"));

        assertThat(targets(TargetPurpose.INTAKE)).isEmpty();
    }
}
