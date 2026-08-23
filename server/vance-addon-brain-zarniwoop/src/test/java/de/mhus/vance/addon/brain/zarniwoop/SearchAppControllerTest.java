package de.mhus.vance.addon.brain.zarniwoop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.zarniwoop.SearchProviderFactory;
import de.mhus.vance.brain.zarniwoop.ZarniwoopInsightsService;
import de.mhus.vance.brain.zarniwoop.ZarniwoopResearchService;
import de.mhus.vance.brain.zarniwoop.ZarniwoopService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ContentReference;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchTier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

/**
 * The controller is a mapping layer, so these tests are about the mapping
 * decisions that are easy to get wrong and invisible when they are: which
 * request actually reaches the dispatcher, and what the surface is told it may
 * offer for a given hit.
 */
class SearchAppControllerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";

    private ZarniwoopService zarniwoopService;
    private SearchApplication application;
    private RequestAuthority authority;
    private HttpServletRequest request;
    private SearchAppController controller;

    @BeforeEach
    void setUp() {
        zarniwoopService = mock(ZarniwoopService.class);
        application = mock(SearchApplication.class);
        authority = mock(RequestAuthority.class);
        request = mock(HttpServletRequest.class);
        controller = new SearchAppController(
                zarniwoopService,
                mock(ZarniwoopResearchService.class),
                mock(ZarniwoopInsightsService.class),
                mock(SearchProviderFactory.class),
                application,
                authority);
        when(zarniwoopService.search(any(), any(), any())).thenReturn(emptyResult());
    }

    // ── authorisation ────────────────────────────────────────────────

    @Test
    void search_enforcesProjectRead() {
        // A search reads a foreign index and writes nothing here.
        controller.search(TENANT, PROJECT, null, req("tariffs"), request);

        verify(authority).enforce(request,
                new Resource.Project(TENANT, PROJECT), Action.READ);
    }

    @Test
    void search_blankQueryIsRefused() {
        assertThatThrownBy(() -> controller.search(TENANT, PROJECT, null, req("   "), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
    }

    // ── what reaches the dispatcher ──────────────────────────────────

    @Test
    void search_withoutAFolderUsesTheBuiltInDefaultsAndReadsNoManifest() {
        // A search before anything is stored is legitimate — the surface may be
        // previewing.
        controller.search(TENANT, PROJECT, null, req("tariffs"), request);

        assertThat(captured().modality()).isEqualTo(SearchConfig.FALLBACK_MODALITY);
        assertThat(captured().maxResults()).isEqualTo(SearchConfig.FALLBACK_NUM);
        verify(application, org.mockito.Mockito.never()).readConfig(any(), any(), any());
    }

    @Test
    void search_takesModalityAndCountFromTheManifestWhenTheRequestIsSilent() {
        when(application.readConfig(TENANT, PROJECT, "desk"))
                .thenReturn(new SearchConfig(SearchModality.NEWS, 9, List.of()));

        controller.search(TENANT, PROJECT, "desk", req("tariffs"), request);

        assertThat(captured().modality()).isEqualTo(SearchModality.NEWS);
        assertThat(captured().maxResults()).isEqualTo(9);
    }

    @Test
    void search_requestOverridesTheManifest() {
        when(application.readConfig(TENANT, PROJECT, "desk"))
                .thenReturn(new SearchConfig(SearchModality.NEWS, 9, List.of()));

        controller.search(TENANT, PROJECT, "desk", new SearchRequestView(
                "tariffs", "academic", null, 3, null, null, null), request);

        assertThat(captured().modality()).isEqualTo(SearchModality.ACADEMIC);
        assertThat(captured().maxResults()).isEqualTo(3);
    }

    @Test
    void search_normalTierDropsExpertParamsAndThePin() {
        // Both are expert-tier only in the dispatcher. Passing them at normal
        // tier would silently do nothing; dropping them here is where the reason
        // can be written down.
        controller.search(TENANT, PROJECT, null, new SearchRequestView(
                "tariffs", "news", "normal", 5, null, "serper-main",
                Map.of("site", "reuters.com")), request);

        assertThat(captured().expertParams()).isEmpty();
        assertThat(captured().pinnedProviderId()).isNull();
    }

    @Test
    void search_expertTierForwardsExpertParamsAndThePin() {
        controller.search(TENANT, PROJECT, null, new SearchRequestView(
                "tariffs", "news", "expert", 5, null, "serper-main",
                Map.of("site", "reuters.com")), request);

        assertThat(captured().tier()).isEqualTo(SearchTier.EXPERT);
        assertThat(captured().pinnedProviderId()).isEqualTo("serper-main");
        assertThat(captured().expertParams()).containsEntry("site", "reuters.com");
    }

    @Test
    void search_unreadableTierFallsBackToNormalRatherThanRefusing() {
        controller.search(TENANT, PROJECT, null, new SearchRequestView(
                "tariffs", null, "expret", null, null, null, null), request);

        assertThat(captured().tier()).isEqualTo(SearchTier.NORMAL);
    }

    @Test
    void search_forwardsAReadableLocaleAndIgnoresAnUnreadableOne() {
        controller.search(TENANT, PROJECT, null, new SearchRequestView(
                "tariffs", null, null, null, "de-DE", null, null), request);
        assertThat(captured().locale()).isNotNull()
                .satisfies(l -> assertThat(l.getLanguage()).isEqualTo("de"));

        // A locale is a hint to the provider; losing the hint beats losing the
        // search.
        controller.search(TENANT, PROJECT, null, new SearchRequestView(
                "tariffs", null, null, null, "!!!", null, null), request);
        assertThat(captured().locale()).isNull();
    }

    @Test
    void search_doesNotCarryAProcessId() {
        // The call comes from a person. Inventing a process id would put a fake
        // process in the research audit log.
        controller.search(TENANT, PROJECT, null, req("tariffs"), request);

        ArgumentCaptor<de.mhus.vance.toolpack.ToolInvocationContext> ctx =
                ArgumentCaptor.forClass(de.mhus.vance.toolpack.ToolInvocationContext.class);
        verify(zarniwoopService).search(any(), any(), ctx.capture());
        assertThat(ctx.getValue().processId()).isNull();
    }

    // ── the content ladder ───────────────────────────────────────────

    @Test
    void hitWithAnInlineBodyIsReportedAsEmbeddedAndUncapped() {
        // Uncapped on purpose: the LLM path cuts at a thousand characters because
        // characters cost tokens there. A screen has no such budget.
        String body = "x".repeat(5000);
        SearchResultView view = resultWith(new ContentReference(
                "c1", "text/plain", 5000, ContentInline.EMBED_TEXT, body, null));

        assertThat(view.hits()).singleElement().satisfies(h -> {
            assertThat(h.contentState()).isEqualTo(SearchHitView.CONTENT_EMBEDDED);
            assertThat(h.body()).hasSize(5000);
            assertThat(h.contentId()).isEqualTo("c1");
        });
    }

    @Test
    void hitWithAStashedBodyIsReportedAsOnDemandWithNoBody() {
        SearchResultView view = resultWith(new ContentReference(
                "c1", "application/pdf", 90_000, ContentInline.STASH_ON_DEMAND, null, null));

        assertThat(view.hits()).singleElement().satisfies(h -> {
            assertThat(h.contentState()).isEqualTo(SearchHitView.CONTENT_ON_DEMAND);
            assertThat(h.body()).isNull();
            // The surface needs both to offer the fetch and to say what it costs.
            assertThat(h.contentId()).isEqualTo("c1");
            assertThat(h.sizeBytes()).isEqualTo(90_000L);
        });
    }

    @Test
    void hitWithoutContentIsReportedAsNone() {
        // This is what keeps a "load full text" button from appearing where it
        // would fail — the state is read, not guessed.
        SearchResultView view = resultWith(null);

        assertThat(view.hits()).singleElement().satisfies(h -> {
            assertThat(h.contentState()).isEqualTo(SearchHitView.CONTENT_NONE);
            assertThat(h.contentId()).isNull();
        });
    }

    @Test
    void anEmbeddedReferenceWithNoTextIsReportedAsNone() {
        // An empty promise: offering a body that turns out not to exist is worse
        // than offering nothing.
        SearchResultView view = resultWith(new ContentReference(
                "c1", "text/plain", 0, ContentInline.EMBED_TEXT, "  ", null));

        assertThat(view.hits()).singleElement().satisfies(h -> {
            assertThat(h.contentState()).isEqualTo(SearchHitView.CONTENT_NONE);
            assertThat(h.contentId()).isNull();
        });
    }

    @Test
    void hitExtrasSurviveUnchanged() {
        // An image grid is built entirely from these; renaming or nesting them
        // would break every per-modality rendering.
        SearchHit hit = new SearchHit("Image", "https://page.test/1", null, "Flickr",
                SearchModality.IMAGE, null,
                Map.of("imageUrl", "https://cdn.test/1.jpg",
                        "thumbnailUrl", "https://cdn.test/1_t.jpg"));
        when(zarniwoopService.search(any(), any(), any()))
                .thenReturn(resultOf(SearchModality.IMAGE, hit));

        SearchResultView view =
                controller.search(TENANT, PROJECT, null, req("lisbon"), request);

        assertThat(view.hits()).singleElement().satisfies(h -> {
            // url is the page, extras.imageUrl the file — two different links.
            assertThat(h.url()).isEqualTo("https://page.test/1");
            assertThat(h.extras()).containsEntry("imageUrl", "https://cdn.test/1.jpg");
        });
    }

    @Test
    void aDispatcherLevelErrorIsCarriedInTheResultRatherThanThrown() {
        // "No provider could serve this" is an answer about one tab, not a
        // failure of the app.
        when(zarniwoopService.search(any(), any(), any())).thenReturn(
                SearchResult.unavailable(
                        SearchRequest.normal("x", SearchModality.MAP, 5),
                        "no provider instance for modality MAP"));

        SearchResultView view = controller.search(TENANT, PROJECT, null, req("x"), request);

        assertThat(view.error()).contains("MAP");
        assertThat(view.hits()).isEmpty();
    }

    // ── content endpoint ─────────────────────────────────────────────

    @Test
    void content_requiresBothIdentifiers() {
        assertThatThrownBy(() -> controller.content(TENANT, PROJECT,
                new ContentRequestView("", "c1", null), request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.content(TENANT, PROJECT,
                new ContentRequestView("serper-main", "  ", null), request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void content_unknownEndpointIsARefusalNotAServerError() {
        // The factory returns no instances for this project, so the id cannot be
        // resolved — the caller asked for something that does not exist.
        assertThatThrownBy(() -> controller.content(TENANT, PROJECT,
                new ContentRequestView("nope", "c1", null), request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private SearchRequest captured() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(zarniwoopService, org.mockito.Mockito.atLeastOnce())
                .search(captor.capture(), any(), any());
        return captor.getValue();
    }

    private SearchResultView resultWith(@Nullable ContentReference content) {
        when(zarniwoopService.search(any(), any(), any()))
                .thenReturn(resultOf(SearchModality.ACADEMIC, hit(content)));
        return controller.search(TENANT, PROJECT, null, req("x"), request);
    }

    // ── the mime clamp on the content endpoint ───────────────────────

    @Test
    void safeMediaType_clampsAnythingNotOnTheAllowList() {
        // These bytes come from a foreign service and render on the brain's own
        // origin: text/html and image/svg+xml both carry script, so agreeing
        // with the source about either is a choice we do not have to make.
        assertThat(SearchAppController.safeMediaType("text/html"))
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(SearchAppController.safeMediaType("image/svg+xml"))
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void safeMediaType_clampsATypeItCannotParse() {
        assertThat(SearchAppController.safeMediaType("not a media type"))
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    void safeMediaType_rebuildsFromTheEssenceSoNoParameterRidesAlong() {
        MediaType clamped = SearchAppController.safeMediaType(
                "application/pdf; boundary=--x; foo=bar");

        assertThat(clamped.getType()).isEqualTo("application");
        assertThat(clamped.getSubtype()).isEqualTo("pdf");
        assertThat(clamped.getParameters()).isEmpty();
    }

    @Test
    void safeMediaType_statesUtf8ForTextAndNothingElse() {
        assertThat(SearchAppController.safeMediaType("text/plain").getCharset())
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(SearchAppController.safeMediaType("image/png").getCharset()).isNull();
    }

    // ── a null in a parameter map ────────────────────────────────────

    @Test
    void search_aNullExpertParamIsDroppedRatherThanCrashing() {
        // SearchRequest copies its maps with Map.copyOf, which throws on a null
        // value — the caller got a 500 for a request the documented contract
        // answers with 409.
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("site", null);
        params.put("desk", "world");
        when(zarniwoopService.search(any(), any(), any())).thenReturn(emptyResult());

        controller.search(TENANT, PROJECT, null, new SearchRequestView(
                "tariffs", null, "expert", null, null, null, params), request);

        assertThat(captured().expertParams()).containsExactly(Map.entry("desk", "world"));
    }

    private static SearchRequestView req(String query) {
        return new SearchRequestView(query, null, null, null, null, null, null);
    }

    private static SearchHit hit(@Nullable ContentReference content) {
        return new SearchHit("Paper", "https://a.test/1", "teaser", "OpenAlex",
                SearchModality.ACADEMIC, content, Map.of());
    }

    private static SearchResult resultOf(SearchModality modality, SearchHit... hits) {
        return new SearchResult("x", modality, "endpoint-1", SearchTier.NORMAL,
                List.of(hits), hits.length, 0, null, null, Map.of());
    }

    private static SearchResult emptyResult() {
        return resultOf(SearchModality.WEB);
    }
}
