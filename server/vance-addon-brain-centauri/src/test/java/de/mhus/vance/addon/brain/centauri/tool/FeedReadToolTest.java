package de.mhus.vance.addon.brain.centauri.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.addon.brain.centauri.FeedsApplication;
import de.mhus.vance.brain.centauri.CentauriItem;
import de.mhus.vance.brain.centauri.CentauriNote;
import de.mhus.vance.brain.centauri.CentauriPage;
import de.mhus.vance.brain.centauri.CentauriPageRequest;
import de.mhus.vance.brain.centauri.CentauriService;
import de.mhus.vance.brain.tools.eddie.EddieContext;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The tool's own job is the boundary: what a model may pass, what reaches the
 * dispatcher, and how much of an entry lands back in the prompt.
 */
class FeedReadToolTest {

    private static final ToolInvocationContext CTX =
            new ToolInvocationContext("acme", "news", null, null, "marvin");

    private final EddieContext eddieContext = mock(EddieContext.class);
    private final CentauriService centauriService = mock(CentauriService.class);
    private final FeedsApplication application = mock(FeedsApplication.class);
    private final ProjectDocument project = mock(ProjectDocument.class);

    private final ArgumentCaptor<CentauriPageRequest> request =
            ArgumentCaptor.forClass(CentauriPageRequest.class);

    private FeedReadTool tool;

    @BeforeEach
    void setUp() {
        tool = new FeedReadTool(eddieContext, centauriService, application);
        when(project.getName()).thenReturn("news");
        when(eddieContext.resolveProject(any(), any(), anyBoolean())).thenReturn(project);
        when(centauriService.fetchPage(any(), any())).thenReturn(page());
    }

    @Test
    void invoke_withoutFolderOrStreams_pointsAtFeedSources() {
        // The failure this guards is a model inventing a source id; the message has
        // to say where the real ones come from.
        assertThatThrownBy(() -> tool.invoke(Map.of(), CTX))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("feed_sources");
    }

    @Test
    void invoke_explicitStreams_reachTheDispatcher() {
        tool.invoke(Map.of("streams", List.of(
                Map.of("source", "wikipedia-de", "selector", "article"),
                "usgs")), CTX);

        verify(centauriService).fetchPage(request.capture(), any());
        assertThat(request.getValue().streams())
                .extracting(s -> s.sourceId() + "|" + s.selector())
                .containsExactly("wikipedia-de|article", "usgs|");
    }

    @Test
    void invoke_limitIsCapped() {
        tool.invoke(Map.of("streams", List.of("usgs"), "limit", 500), CTX);

        verify(centauriService).fetchPage(request.capture(), any());
        // A page lands in a prompt — the caller does not get to decide how big.
        assertThat(request.getValue().pageSize()).isEqualTo(FeedReadTool.MAX_LIMIT);
    }

    @Test
    void invoke_mapsEntriesCompactly() {
        Map<String, Object> out = tool.invoke(Map.of("streams", List.of("usgs")), CTX);

        assertThat(out).containsEntry("count", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) out.get("items")).get(0);
        assertThat(item).containsEntry("title", "M 4.7 — somewhere")
                .containsEntry("source", "usgs")
                .containsEntry("publishedAt", "2026-08-19T10:00:00Z")
                .containsKey("summary");
        // No cursor is handed out: a model would fabricate one, and a fabricated
        // cursor is rejected.
        assertThat(item).doesNotContainKeys("cursor", "id");
    }

    @Test
    void invoke_longSummary_isTrimmed() {
        when(centauriService.fetchPage(any(), any())).thenReturn(pageWithLongSummary());

        Map<String, Object> out = tool.invoke(Map.of("streams", List.of("usgs")), CTX);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = ((List<Map<String, Object>>) out.get("items")).get(0);
        assertThat((String) item.get("summary"))
                .hasSize(FeedReadTool.SUMMARY_LIMIT + 1)
                .endsWith("…");
    }

    @Test
    void invoke_unavailableStreams_areReportedNotSwallowed() {
        when(centauriService.fetchPage(any(), any())).thenReturn(pageWithNote());

        Map<String, Object> out = tool.invoke(Map.of("streams", List.of("usgs")), CTX);

        // A digest that silently omits a source would read as "nothing happened
        // there", which is a different statement.
        assertThat(out.get("unavailable").toString()).contains("cooling_down");
    }

    @Test
    void resolveSince_understandsRelativeAndAbsolute() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");

        assertThat(FeedReadTool.resolveSince("-24h", now))
                .isEqualTo(Instant.parse("2026-08-18T12:00:00Z"));
        assertThat(FeedReadTool.resolveSince("-7d", now))
                .isEqualTo(Instant.parse("2026-08-12T12:00:00Z"));
        assertThat(FeedReadTool.resolveSince("-30m", now))
                .isEqualTo(Instant.parse("2026-08-19T11:30:00Z"));
        assertThat(FeedReadTool.resolveSince("2026-08-01T00:00:00Z", now))
                .isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(FeedReadTool.resolveSince(null, now)).isNull();
    }

    @Test
    void resolveSince_garbage_saysWhatIsAccepted() {
        assertThatThrownBy(() -> FeedReadTool.resolveSince("yesterday", Instant.now()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("-24h");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static CentauriPage page() {
        return new CentauriPage(List.of(item("short summary")), null, false, List.of(), 0, 0);
    }

    private static CentauriPage pageWithLongSummary() {
        return new CentauriPage(List.of(item("x".repeat(FeedReadTool.SUMMARY_LIMIT + 50))),
                null, false, List.of(), 0, 0);
    }

    private static CentauriPage pageWithNote() {
        return new CentauriPage(List.of(), null, false,
                List.of(new CentauriNote("usgs", "all", CentauriNote.Kind.COOLING_DOWN, null)),
                0, 0);
    }

    private static CentauriItem item(String summary) {
        FeedItem feedItem = new FeedItem(
                "us1", Instant.parse("2026-08-19T10:00:00Z"), "M 4.7 — somewhere",
                "https://earthquake.usgs.gov/1", summary, null, null, null, null, null,
                List.of("earthquake"), Map.of());
        return new CentauriItem(feedItem, "usgs", "USGS earthquakes", "all");
    }
}
