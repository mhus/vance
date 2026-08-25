package de.mhus.vance.addon.brain.zarniwoop;

import de.mhus.vance.api.insights.ZarniwoopInsightsDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.zarniwoop.SearchProviderFactory;
import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.brain.zarniwoop.ZarniwoopInsightsService;
import de.mhus.vance.brain.zarniwoop.ZarniwoopResearchService;
import de.mhus.vance.brain.zarniwoop.ZarniwoopService;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.ContentInline;
import de.mhus.vance.toolpack.research.ContentReference;
import de.mhus.vance.toolpack.research.DroppedHit;
import de.mhus.vance.toolpack.research.LoadedContent;
import de.mhus.vance.toolpack.research.RankedHit;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchHit;
import de.mhus.vance.toolpack.research.SearchModality;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchRequest;
import de.mhus.vance.toolpack.research.SearchResult;
import de.mhus.vance.toolpack.research.SearchScope;
import de.mhus.vance.toolpack.research.SearchTier;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the search app under
 * {@code /brain/{tenant}/addon/search/...}.
 *
 * <p>Thin on purpose: the dispatch cascade, the providers, the cooldowns and the
 * curated pipeline all live in {@code de.mhus.vance.brain.zarniwoop}. What is
 * here is the web-facing shape — authorisation, the manifest as configuration
 * store, and the mapping from the search contract to what a screen needs.
 *
 * <p><b>Two things this deliberately does not do.</b> It does not fan out over
 * modalities (the surface asks per tab, so each tab loads and fails on its own),
 * and it does not fetch a web page behind a hit — the jump-out is the answer, and
 * proxying a foreign page through the brain would cost bandwidth and an SSRF
 * surface to show what a new tab shows better.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SearchAppController {

    private final ZarniwoopService zarniwoopService;
    private final ZarniwoopResearchService researchService;
    private final ZarniwoopInsightsService insightsService;
    private final SearchProviderFactory providerFactory;
    private final SearchApplication application;
    private final RequestAuthority authority;

    /**
     * The provider endpoints of this project — modalities, tiers, availability,
     * status line, call counts, cooldown.
     *
     * <p>This is what makes the surface capability-gated: no Serper key means no
     * image tab at all, rather than an image tab that always fails. It reuses
     * {@link ZarniwoopInsightsDto} instead of a view of its own — a parallel type
     * carrying the same nine fields would only drift.
     *
     * <p>{@code refresh} drops the factory cache first. Settings just written are
     * invisible for up to five minutes otherwise, and that wait looks exactly
     * like a wrong key.
     */
    @GetMapping("/brain/{tenant}/addon/search/providers")
    public List<ZarniwoopInsightsDto> providers(@PathVariable String tenant,
                                                @RequestParam String projectId,
                                                @RequestParam(defaultValue = "false")
                                                boolean refresh,
                                                HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return insightsService.listInstances(tenant, projectId, refresh);
    }

    @GetMapping("/brain/{tenant}/addon/search/config")
    public SearchConfigView config(@PathVariable String tenant,
                                   @RequestParam String projectId,
                                   @RequestParam String folder,
                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return toView(SearchApplication.normaliseFolder(folder),
                application.readManifest(tenant, projectId, folder).title(),
                application.readConfig(tenant, projectId, folder));
    }

    @PutMapping("/brain/{tenant}/addon/search/config")
    public SearchConfigView saveConfig(@PathVariable String tenant,
                                       @RequestParam String projectId,
                                       @RequestParam String folder,
                                       @RequestBody SearchConfigView body,
                                       HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        application.writeConfig(tenant, projectId, folder, fromView(body), currentUser(request));
        return config(tenant, projectId, folder, request);
    }

    /**
     * One search, one modality.
     *
     * <p>{@code READ} on the project: a search reads a foreign index and writes
     * nothing here. It does spend provider quota, which is a cost question the
     * surface answers (explicit submit, visible quota) and not an authorisation
     * one — a reader who may see the project's research configuration may use it.
     */
    @PostMapping("/brain/{tenant}/addon/search/search")
    public SearchResultView search(@PathVariable String tenant,
                                   @RequestParam String projectId,
                                   @RequestParam(required = false) @Nullable String folder,
                                   @RequestBody SearchRequestView body,
                                   HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        if (body == null || StringUtils.isBlank(body.query())) {
            throw new IllegalArgumentException("query is required");
        }
        SearchScope scope = scope(tenant, projectId, request);

        // The manifest supplies the defaults only when a folder was named. A
        // search without one is still valid — the surface may be previewing
        // before anything is stored.
        SearchConfig config = StringUtils.isBlank(folder)
                ? SearchConfig.empty()
                : application.readConfig(tenant, projectId, folder);

        SearchModality modality = body.modality() == null
                ? config.defaultModality()
                : SearchConfig.modality(body.modality());
        SearchTier tier = SearchConfig.tier(body.tier());
        int num = body.num() == null || body.num() <= 0 ? config.defaultNum() : body.num();

        SearchRequest req = new SearchRequest(
                body.query().trim(), modality, tier, num,
                locale(body.locale()),
                // Pinning is expert-tier only in the dispatcher; passing it at
                // normal tier would silently do nothing, so it is dropped here
                // where the reason can be written down.
                tier == SearchTier.EXPERT ? blankToNull(body.instance()) : null,
                tier == SearchTier.EXPERT ? withoutNulls(body.expertParams()) : Map.of(),
                withoutNulls(body.facets()));

        SearchResult result = zarniwoopService.search(req, scope, toolContext(scope));
        return toView(result);
    }

    /**
     * The body behind a hit, for a source that serves one on request.
     *
     * <p>Returns the <b>bytes</b> and never the stash path. The path lives in the
     * project workspace temp-root, whose lifecycle belongs to
     * {@code WorkspaceService.suspendAll} — putting it in a client response would
     * make a transient file part of this app's contract.
     *
     * <p>Note what this cannot do today: of the built-in protocols, <b>none</b>
     * implements {@code loadContent} — only an {@code ode} source does. The
     * surface knows that from {@code contentState} on each hit and does not offer
     * the button elsewhere, so this endpoint is reachable but rarely useful until
     * a later phase adds Wikipedia and PubMed.
     */
    @PostMapping("/brain/{tenant}/addon/search/content")
    public ResponseEntity<byte[]> content(@PathVariable String tenant,
                                          @RequestParam String projectId,
                                          @RequestBody ContentRequestView body,
                                          HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        if (body == null || StringUtils.isBlank(body.instanceId())
                || StringUtils.isBlank(body.contentId())) {
            throw new IllegalArgumentException("instanceId and contentId are required");
        }
        SearchScope scope = scope(tenant, projectId, request);

        SearchProviderInstance instance = findInstance(scope, body.instanceId());
        String mime = StringUtils.isBlank(body.mimeType())
                ? "application/octet-stream" : body.mimeType().trim();

        // Rebuilt rather than remembered: holding a per-hit reference in memory
        // would tie the click to whichever pod answered the search.
        ContentReference ref = new ContentReference(
                body.contentId(), mime, 0L, ContentInline.STASH_ON_DEMAND, null, null);

        LoadedContent loaded;
        try {
            loaded = instance.loadContent(ref, scope);
        } catch (UnsupportedOperationException e) {
            // The provider never implemented it. A 409 rather than a 500: this
            // is the caller asking for something this source does not do.
            throw new IllegalArgumentException("provider '" + body.instanceId()
                    + "' does not serve hit bodies");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(loaded.stashPath());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not read stashed content of '" + body.contentId() + "'", e);
        }
        return ResponseEntity.ok()
                // The type is clamped, not echoed. These bytes come from a
                // foreign service and are rendered by the browser on the brain's
                // own origin, so letting the far end pick text/html or
                // image/svg+xml would let it run script next to the session it
                // was fetched with. Anything not on the allow-list is served as
                // an opaque download instead of being refused — a body we cannot
                // safely inline is still a body someone may want.
                .contentType(safeMediaType(loaded.mimeType()))
                // Belt to the same braces: without this a browser may sniff past
                // the declared type and render the very markup the clamp removed.
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(bytes);
    }

    /**
     * The curated pipeline: plan, search several sources, evaluate, rank.
     *
     * <p>Costs provider quota <b>and</b> LLM tokens and takes seconds, which is
     * why the surface has to present it as its own named action. As the default
     * search button it would be a cost trap.
     */
    @PostMapping("/brain/{tenant}/addon/search/investigate")
    public InvestigateResultView investigate(@PathVariable String tenant,
                                            @RequestParam String projectId,
                                            @RequestBody InvestigateRequestView body,
                                            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        if (body == null || StringUtils.isBlank(body.question())) {
            throw new IllegalArgumentException("question is required");
        }
        SearchScope scope = scope(tenant, projectId, request);
        RankedHitSet ranked = researchService.investigate(
                body.question().trim(), scope, toolContext(scope));
        return toView(ranked);
    }

    /**
     * A refused request is the caller's problem, not a server fault — and a 5xx
     * would invite a retry of something that cannot succeed. Covers both a
     * malformed body and a dispatcher refusal (no project scope, unknown
     * endpoint).
     */
    @ExceptionHandler({ZarniwoopException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> onRefused(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }

    // ── mapping ──────────────────────────────────────────────────────

    private static SearchResultView toView(SearchResult result) {
        List<SearchHitView> hits = new ArrayList<>(result.hits().size());
        for (SearchHit hit : result.hits()) {
            hits.add(toView(hit));
        }
        return new SearchResultView(
                result.query(),
                result.modality().name().toLowerCase(Locale.ROOT),
                result.tier().name().toLowerCase(Locale.ROOT),
                result.providerInstanceId(),
                hits,
                result.droppedCount(),
                result.note(),
                result.errorMessage());
    }

    /**
     * The body is passed through <b>uncapped</b>, unlike the LLM path which cuts
     * it at a thousand characters. Characters cost tokens in a prompt and nothing
     * on a screen.
     */
    private static SearchHitView toView(SearchHit hit) {
        ContentReference content = hit.content();
        String state = SearchHitView.CONTENT_NONE;
        String body = null;
        String contentId = null;
        String mimeType = null;
        Long sizeBytes = null;
        if (content != null) {
            contentId = content.contentId();
            mimeType = content.mimeType();
            sizeBytes = content.sizeBytes() > 0 ? content.sizeBytes() : null;
            if (content.inline() == ContentInline.EMBED_TEXT
                    && !StringUtils.isBlank(content.inlineText())) {
                state = SearchHitView.CONTENT_EMBEDDED;
                body = content.inlineText();
            } else if (content.inline() == ContentInline.STASH_ON_DEMAND) {
                state = SearchHitView.CONTENT_ON_DEMAND;
            } else {
                // An EMBED_TEXT reference with no text is an empty promise;
                // reporting `none` keeps the surface from offering a body it
                // cannot show.
                contentId = null;
                mimeType = null;
                sizeBytes = null;
            }
        }
        return new SearchHitView(
                hit.title(), hit.url(), hit.snippet(), hit.source(),
                hit.modality().name().toLowerCase(Locale.ROOT),
                body, contentId, state, mimeType, sizeBytes,
                hit.extras() == null ? Map.of() : hit.extras());
    }

    private static InvestigateResultView toView(RankedHitSet ranked) {
        List<RankedHitView> hits = new ArrayList<>(ranked.keptHits().size());
        for (RankedHit hit : ranked.keptHits()) {
            hits.add(new RankedHitView(
                    hit.title(), hit.url(),
                    hit.modality().name().toLowerCase(Locale.ROOT),
                    hit.providerInstanceId(),
                    hit.finalScore(), hit.relevanceScore(),
                    hit.snippet(), hit.relevanceNote(),
                    hit.extras() == null ? Map.of() : hit.extras()));
        }
        List<String> instances = new ArrayList<>(ranked.instancesUsed());
        instances.sort(String::compareTo);
        return new InvestigateResultView(
                ranked.question(), hits,
                ranked.droppedHits().size(),
                instances,
                ranked.gaps());
    }

    private static SearchConfigView toView(
            String folder, @Nullable String title, SearchConfig config) {
        List<SavedSearchView> saved = new ArrayList<>(config.savedSearches().size());
        for (SearchConfig.SavedSearch s : config.savedSearches()) {
            saved.add(new SavedSearchView(s.name(), s.query(),
                    s.modality().name().toLowerCase(Locale.ROOT),
                    s.tier().name().toLowerCase(Locale.ROOT),
                    s.instance()));
        }
        return new SearchConfigView(folder, title,
                config.defaultModality().name().toLowerCase(Locale.ROOT),
                config.defaultNum(), saved);
    }

    private static SearchConfig fromView(SearchConfigView view) {
        if (view == null) {
            return SearchConfig.empty();
        }
        List<SearchConfig.SavedSearch> saved = new ArrayList<>();
        if (view.savedSearches() != null) {
            for (SavedSearchView s : view.savedSearches()) {
                if (s == null || StringUtils.isBlank(s.name())
                        || StringUtils.isBlank(s.query())) {
                    // Skipping an unusable row beats refusing the whole save and
                    // losing the rows that were fine.
                    continue;
                }
                saved.add(new SearchConfig.SavedSearch(s.name().trim(), s.query().trim(),
                        SearchConfig.modality(s.modality()), SearchConfig.tier(s.tier()),
                        blankToNull(s.instance())));
            }
        }
        return new SearchConfig(
                SearchConfig.modality(view.defaultModality()), view.defaultNum(), saved);
    }

    // ── internals ────────────────────────────────────────────────────

    private SearchProviderInstance findInstance(SearchScope scope, String instanceId) {
        for (SearchProviderInstance instance : providerFactory.assemble(scope)) {
            if (instance.id().equals(instanceId)) {
                return instance;
            }
        }
        throw new IllegalArgumentException("unknown provider endpoint '" + instanceId + "'");
    }

    /**
     * Types a hit body may be served as, everything else becoming an opaque
     * download.
     *
     * <p>An allow-list rather than a block-list of the dangerous ones, because
     * the dangerous set is not enumerable — {@code text/html},
     * {@code image/svg+xml}, {@code application/xhtml+xml} and
     * {@code text/xml} all execute script in a browser, and the next such type
     * would arrive without anyone editing this file. {@code image/svg+xml} is
     * deliberately absent even though it is an image: it carries script.
     */
    private static final Set<String> INLINE_SAFE_TYPES = Set.of(
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/pdf",
            "application/json",
            "image/png",
            "image/jpeg",
            "image/gif",
            "image/webp");

    /**
     * The type these bytes will be served as: what the source said, if that is
     * on {@link #INLINE_SAFE_TYPES}, otherwise {@code application/octet-stream}.
     *
     * <p>Clamping rather than trusting is the whole point. The source is foreign
     * software, the response renders on the brain's origin, and a body announced
     * as a PDF in the search result may arrive as HTML here — the two statements
     * come from the same untrusted place and agreeing with either is a choice we
     * do not have to make.
     */
    static MediaType safeMediaType(String raw) {
        MediaType parsed;
        try {
            parsed = MediaType.parseMediaType(raw);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String essence = parsed.getType().toLowerCase(Locale.ROOT)
                + "/" + parsed.getSubtype().toLowerCase(Locale.ROOT);
        if (!INLINE_SAFE_TYPES.contains(essence)) {
            log.debug("Search app: hit body declared '{}' — serving it as an opaque "
                    + "download instead", essence);
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        // Rebuilt from the essence so a boundary or other parameter the source
        // attached cannot ride along. UTF-8 is stated for text, where the bytes
        // are decoded and the browser would otherwise guess; declaring a charset
        // on a PDF or an image would only be noise.
        return essence.startsWith("text/")
                ? new MediaType(parsed.getType(), parsed.getSubtype(), StandardCharsets.UTF_8)
                : new MediaType(parsed.getType(), parsed.getSubtype());
    }

    /**
     * An unreadable language tag is ignored rather than refused: the locale is a
     * hint to the provider, and losing the hint is better than losing the search.
     */
    private static @Nullable Locale locale(@Nullable String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            Locale parsed = Locale.forLanguageTag(raw.trim());
            return parsed.getLanguage().isEmpty() ? null : parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String blankToNull(@Nullable String raw) {
        return StringUtils.isBlank(raw) ? null : raw.trim();
    }

    /**
     * A parameter map from a JSON body, without the entries whose value is
     * {@code null}.
     *
     * <p>{@code SearchRequest}'s compact constructor uses {@code Map.copyOf},
     * which throws on a null value — so
     * {@code {"expertParams":{"site":null}}} came back as a 500, and a 500
     * tells the client to retry something that can never work. The documented
     * shape of a refused request here is 409. Same treatment as
     * {@code ResearchSearchExpertTool.copyDeclaredParams}.
     */
    private static <V> Map<String, V> withoutNulls(@Nullable Map<String, V> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, V> out = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, V> e : raw.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static SearchScope scope(
            String tenant, String projectId, HttpServletRequest request) {
        return new SearchScope(tenant, projectId, null, currentUser(request));
    }

    /**
     * The dispatcher wants a tool context for its audit log and failure triage.
     * No process id — this call comes from a person, not from a think process,
     * and inventing one would put a fake process in the research log.
     */
    private static ToolInvocationContext toolContext(SearchScope scope) {
        return new ToolInvocationContext(
                scope.tenantId(), scope.projectId(), null, null, scope.userId());
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        // One spelling for "who is doing this". Reading the attribute by
        // hand is what put the wrong name here: nothing ever set
        // "vanceUserId", so every actor recorded from this request was null.
        return AccessFilterBase.usernameOrNull(req);
    }
}
