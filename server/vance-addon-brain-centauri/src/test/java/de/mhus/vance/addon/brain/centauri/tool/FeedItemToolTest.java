package de.mhus.vance.addon.brain.centauri.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.centauri.CentauriService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One entry, in full. What matters here is the boundary: an entry the source
 * has forgotten is an answer rather than a failure, and a body that has not
 * been fetched is a different thing again.
 */
class FeedItemToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "news", null, null, "marvin");

    private final EddieContext eddieContext = mock(EddieContext.class);
    private final CentauriService centauriService = mock(CentauriService.class);
    private final ProjectDocument project = mock(ProjectDocument.class);

    private FeedItemTool tool;

    @BeforeEach
    void setUp() {
        tool = new FeedItemTool(eddieContext, centauriService);
        when(project.getName()).thenReturn("news");
        when(eddieContext.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
    }

    @Test
    void invoke_returnsTheBodyAndWhatTheSourceAdded() {
        when(centauriService.loadItem(any(), any(), any())).thenReturn(Optional.of(item(
                "the whole article", Map.of("originPlace", "Germany"))));

        Map<String, Object> out = tool.invoke(
                Map.of("sourceId", "hrafnagud", "itemId", "i1"), CTX);

        assertThat(out).containsEntry("found", true);
        assertThat(out).containsEntry("body", "the whole article");
        assertThat(out.get("extras")).isEqualTo(Map.of("originPlace", "Germany"));
        assertThat(out).doesNotContainKey("bodyHint");
    }

    @Test
    void invoke_unknownEntry_isAnAnswerNotAnError() {
        when(centauriService.loadItem(any(), any(), any())).thenReturn(Optional.empty());

        Map<String, Object> out = tool.invoke(
                Map.of("sourceId", "hrafnagud", "itemId", "gone"), CTX);

        // An entry can age out between the page and the question about it.
        // Throwing would tell the model something broke.
        assertThat(out).containsEntry("found", false);
        assertThat(out.get("hint").toString()).contains("aged out");
    }

    @Test
    void invoke_entryWithoutFullText_saysSoRatherThanLookingEmpty() {
        when(centauriService.loadItem(any(), any(), any()))
                .thenReturn(Optional.of(item(null, Map.of())));

        Map<String, Object> out = tool.invoke(
                Map.of("sourceId", "hrafnagud", "itemId", "i1"), CTX);

        assertThat(out).containsEntry("found", true);
        assertThat(out).doesNotContainKey("body");
        assertThat(out.get("bodyHint").toString()).contains("own schedule");
    }

    @Test
    void invoke_collapsesTheTitle_becauseItComesFromAForeignSource() {
        when(centauriService.loadItem(any(), any(), any())).thenReturn(Optional.of(
                new FeedItem("i1", null, Instant.parse("2026-08-19T10:00:00Z"),
                        "Head\nline   spaced", "https://x.test/1", null, null, null, null,
                        null, null, List.of(), Map.of())));

        Map<String, Object> out = tool.invoke(
                Map.of("sourceId", "hrafnagud", "itemId", "i1"), CTX);

        assertThat(out).containsEntry("title", "Head line spaced");
    }

    @Test
    void invoke_withoutIds_isRefused() {
        assertThatThrownBy(() -> tool.invoke(Map.of("sourceId", "hrafnagud"), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("itemId");
    }

    @Test
    void invoke_collapsesEveryForeignFieldNotJustTheTitle() {
        // extras, body, author and tags all come verbatim from the far end via
        // OdeFeedInstance, and this map goes into a prompt. Hardening the title
        // and leaving the rest is a hole in the wall the title stands behind:
        // a source could inject headings and line breaks through an extras
        // value. Same rule as SearchHitRows on the research side.
        when(centauriService.loadItem(any(), any(), any())).thenReturn(
                Optional.of(new FeedItem("i1", null, Instant.parse("2026-08-19T10:00:00Z"),
                        "Headline", "https://x.test/1", "teaser",
                        "body\n\n## Injected heading", "A.\nAuthor", "en",
                        null, null, List.of("one\ntag"),
                        Map.of("originPlace", "Germany\n\n## Also injected", "score", 3))));

        Map<String, Object> out = tool.invoke(
                Map.of("sourceId", "hrafnagud", "itemId", "i1"), CTX);

        assertThat(out).containsEntry("body", "body ## Injected heading");
        assertThat(out).containsEntry("author", "A. Author");
        assertThat(out).containsEntry("tags", List.of("one tag"));
        assertThat(out.get("extras")).isEqualTo(Map.of(
                "originPlace", "Germany ## Also injected",
                // Numbers carry no structure a template could be broken with.
                "score", 3));
    }

    private static FeedItem item(String body, Map<String, Object> extras) {
        return new FeedItem("i1", null, Instant.parse("2026-08-19T10:00:00Z"),
                "Headline", "https://x.test/1", "teaser", body, "A. Author", "en",
                null, null, List.of("tag"), extras);
    }
}
