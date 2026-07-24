package de.mhus.vance.addon.brain.wiki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.document.DocumentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The space-aware {@code [[…]]} resolution cascade and the inbound-link
 * (backlink) graph are pure over a {@link WikiFolderReader.Scan}; both drive
 * link routing and the red-link UX. Pin the cascade order + ambiguity rule
 * and the backlink dedup / self-link-drop contract.
 */
class WikiServiceTest {

    private final WikiService service = new WikiService(
            mock(DocumentService.class), mock(WikiFolderReader.class),
            mock(SecurityContextFactory.class));

    private static WikiPage page(String space, String slug, String relPath, WikiLink... links) {
        return new WikiPage(null, relPath, space, slug, WikiFolderReader.humanise(slug),
                /*main*/ false, List.of(links));
    }

    private static WikiFolderReader.Scan scan(WikiPage... pages) {
        return new WikiFolderReader.Scan("wiki", null, null, List.of(), List.of(pages));
    }

    // ── resolve cascade ──────────────────────────────────────────────────

    @Test
    void resolve_explicitSpace_wins() {
        WikiFolderReader.Scan s = scan(
                page("docs", "setup", "docs/setup.md"),
                page("ops", "setup", "ops/setup.md"));

        WikiService.Resolution r = service.resolve(s, "docs", "ops/Setup");

        assertThat(r.exists()).isTrue();
        assertThat(r.page().relativePath()).isEqualTo("ops/setup.md");
        assertThat(r.ambiguous()).isFalse();
    }

    @Test
    void resolve_currentSpace_beforeGlobal() {
        WikiFolderReader.Scan s = scan(
                page("docs", "intro", "docs/intro.md"),
                page("ops", "intro", "ops/intro.md"));

        WikiService.Resolution r = service.resolve(s, "ops", "Intro");

        assertThat(r.page().relativePath()).isEqualTo("ops/intro.md");
        assertThat(r.ambiguous()).isFalse();
    }

    @Test
    void resolve_globallyUniqueSlug_matches() {
        WikiFolderReader.Scan s = scan(page("docs", "faq", "docs/faq.md"));

        WikiService.Resolution r = service.resolve(s, "ops", "FAQ");

        assertThat(r.exists()).isTrue();
        assertThat(r.page().relativePath()).isEqualTo("docs/faq.md");
        assertThat(r.ambiguous()).isFalse();
    }

    @Test
    void resolve_ambiguousGlobal_firstMatchPlusFlag() {
        WikiFolderReader.Scan s = scan(
                page("docs", "guide", "docs/guide.md"),
                page("ops", "guide", "ops/guide.md"));

        // current space "other" matches neither → falls to global, 2 matches.
        WikiService.Resolution r = service.resolve(s, "other", "Guide");

        assertThat(r.exists()).isTrue();
        assertThat(r.ambiguous()).isTrue();
        assertThat(r.page().relativePath()).isEqualTo("docs/guide.md"); // first
    }

    @Test
    void resolve_noMatch_isRedLink_withCreateSpace() {
        WikiFolderReader.Scan s = scan(page("docs", "faq", "docs/faq.md"));

        WikiService.Resolution r = service.resolve(s, "ops", "Missing Page");

        assertThat(r.exists()).isFalse();
        assertThat(r.page()).isNull();
        assertThat(r.slug()).isEqualTo("missing-page");
        assertThat(r.createSpace()).isEqualTo("ops");
    }

    @Test
    void resolve_explicitSpaceNoMatch_isRedLink() {
        WikiFolderReader.Scan s = scan(page("docs", "faq", "docs/faq.md"));

        WikiService.Resolution r = service.resolve(s, "docs", "ops/faq");

        assertThat(r.exists()).isFalse();
        assertThat(r.createSpace()).isEqualTo("ops");
    }

    // ── buildBacklinks ───────────────────────────────────────────────────

    @Test
    void buildBacklinks_recordsInboundEdges() {
        WikiFolderReader.Scan s = scan(
                page("", "home", "home.md", new WikiLink("Guide", null)),
                page("", "guide", "guide.md"));

        Map<String, List<String>> graph = service.buildBacklinks(s);

        assertThat(graph.get("guide.md")).containsExactly("home.md");
    }

    @Test
    void buildBacklinks_dedupsRepeatedLinkFromSameSource() {
        WikiFolderReader.Scan s = scan(
                page("", "home", "home.md",
                        new WikiLink("Guide", null), new WikiLink("Guide", "again")),
                page("", "guide", "guide.md"));

        assertThat(service.buildBacklinks(s).get("guide.md")).containsExactly("home.md");
    }

    @Test
    void buildBacklinks_dropsSelfLink() {
        WikiFolderReader.Scan s = scan(
                page("", "home", "home.md", new WikiLink("Home", null)));

        assertThat(service.buildBacklinks(s)).doesNotContainKey("home.md");
    }
}
