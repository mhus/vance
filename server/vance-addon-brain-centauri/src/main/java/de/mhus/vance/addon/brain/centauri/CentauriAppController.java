package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.brain.centauri.CentauriItem;
import de.mhus.vance.brain.centauri.CentauriNote;
import de.mhus.vance.brain.centauri.CentauriPage;
import de.mhus.vance.brain.centauri.CentauriPageRequest;
import de.mhus.vance.brain.centauri.CentauriService;
import de.mhus.vance.brain.centauri.FeedSourceFactory;
import de.mhus.vance.brain.centauri.FeedStream;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.brain.tools.document.DocumentLinkBuilder;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedDirection;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSelector;
import de.mhus.vance.toolpack.feed.FeedSelectorKind;
import de.mhus.vance.brain.centauri.CentauriException;
import de.mhus.vance.toolpack.feed.FeedReportReason;
import de.mhus.vance.toolpack.feed.FeedRequestKind;
import de.mhus.vance.toolpack.feed.FeedSignalOutcome;
import de.mhus.vance.toolpack.feed.FeedSignalRequest;
import de.mhus.vance.toolpack.feed.FeedSignal;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface of the feeds app under
 * {@code /brain/{tenant}/addon/centauri/...}.
 *
 * <p>Thin on purpose: resolving sources, merging streams and paging all live in
 * {@code CentauriService}. What is here is the web-facing shape — authorisation,
 * the manifest as configuration store, and turning an entry into a document.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class CentauriAppController {

    private static final String MD_MIME = "text/markdown";

    private final CentauriService centauriService;
    private final FeedSourceFactory sourceFactory;
    private final FeedsApplication application;
    private final DocumentService documentService;
    private final DocumentLinkBuilder linkBuilder;
    private final SecurityContextFactory contextFactory;
    private final RequestAuthority authority;

    /**
     * The sources configured for this project, with what each can do.
     *
     * <p>A source that cannot be asked is returned with an {@code error} instead
     * of being dropped, so the configuration form can say why it is unusable.
     */
    @GetMapping("/brain/{tenant}/addon/centauri/sources")
    public List<FeedSourceView> sources(@PathVariable String tenant,
                                        @RequestParam String projectId,
                                        @RequestParam(defaultValue = "false") boolean refresh,
                                        HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        FeedScope scope = scope(tenant, projectId, request);
        if (refresh) {
            // Settings just written are invisible until the factory's TTL expires,
            // and that wait looks exactly like a wrong key. Let the caller say
            // "read again" instead of guessing.
            sourceFactory.evict(scope);
        }

        List<FeedSourceView> out = new ArrayList<>();
        for (FeedSourceInstance instance : sourceFactory.assemble(scope)) {
            try {
                FeedCapabilities caps = instance.capabilities();
                List<FeedSelectorView> selectors = new ArrayList<>();
                for (FeedSelector selector : instance.listSelectors()) {
                    selectors.add(new FeedSelectorView(selector.value(), selector.label(),
                            selector.kind().name(), selector.language()));
                }
                out.add(new FeedSourceView(instance.id(), instance.displayName(),
                        instance.baseUrl(), toView(caps), selectors, null));
            } catch (RuntimeException e) {
                log.warn("Centauri: source '{}' could not be described: {}",
                        instance.id(), e.toString());
                out.add(new FeedSourceView(instance.id(), instance.displayName(),
                        instance.baseUrl(), null, List.of(), String.valueOf(e.getMessage())));
            }
        }
        return out;
    }

    @GetMapping("/brain/{tenant}/addon/centauri/config")
    public FeedConfigView config(@PathVariable String tenant,
                                 @RequestParam String projectId,
                                 @RequestParam String folder,
                                 HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return toView(FeedsApplication.normaliseFolder(folder),
                application.readManifest(tenant, projectId, folder).title(),
                application.readConfig(tenant, projectId, folder));
    }

    @PutMapping("/brain/{tenant}/addon/centauri/config")
    public FeedConfigView saveConfig(@PathVariable String tenant,
                                     @RequestParam String projectId,
                                     @RequestParam String folder,
                                     @RequestBody FeedConfigView body,
                                     HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        application.writeConfig(tenant, projectId, folder, fromView(body), currentUser(request));
        return config(tenant, projectId, folder, request);
    }

    /**
     * One page of the mixed feed. POST because the filter is structured — see
     * {@link FeedPageRequest}.
     */
    @PostMapping("/brain/{tenant}/addon/centauri/page")
    public FeedPageView page(@PathVariable String tenant,
                             @RequestParam String projectId,
                             @RequestBody FeedPageRequest body,
                             HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        FeedScope scope = scope(tenant, projectId, request);

        List<FeedStream> streams;
        FeedFilter filter;
        int pageSize;
        if (body.streams() != null && !body.streams().isEmpty()) {
            // Explicit streams: a preview before anything is stored.
            streams = new ArrayList<>();
            for (FeedStreamView view : body.streams()) {
                streams.add(new FeedStream(view.source(), view.selector()));
            }
            filter = fromView(body.filter()).toFilter(Instant.now());
            pageSize = body.pageSize();
        } else {
            FeedsConfig stored = application.readConfig(tenant, projectId, requireFolder(body));
            streams = stored.streams();
            filter = stored.toFilter(Instant.now());
            pageSize = body.pageSize() > 0 ? body.pageSize() : stored.pageSize();
        }

        CentauriPage page = centauriService.fetchPage(
                new CentauriPageRequest(streams, filter, pageSize, direction(body.direction()),
                        body.cursor()),
                scope);
        return toView(page);
    }

    /**
     * Store one entry as a markdown document — the bridge from transient to
     * permanent. Source, author, date and URL go into the frontmatter, so what
     * the entry was remains answerable after the stream has moved on.
     */
    @PostMapping("/brain/{tenant}/addon/centauri/clip")
    public ResponseEntity<ClipResponse> clip(@PathVariable String tenant,
                                             @RequestParam String projectId,
                                             @RequestBody ClipRequest body,
                                             HttpServletRequest request) {
        authority.enforce(request, new Resource.Document(tenant, projectId, body.targetPath()),
                Action.CREATE);
        String path = normalisePath(body.targetPath());
        if (documentService.findByPath(tenant, projectId, path).isPresent()) {
            return ResponseEntity.status(409).body(new ClipResponse(path, null));
        }

        String markdown = renderClip(body);
        String user = currentUser(request);
        try (InputStream in = new ByteArrayInputStream(
                markdown.getBytes(StandardCharsets.UTF_8))) {
            DocumentDocument stored = documentService.create(tenant, projectId, path,
                    body.title(), List.of("clip", "feed"), MD_MIME, in, user,
                    contextFactory.writeActor(tenant, user, path));
            log.info("Centauri: clipped '{}' into '{}/{}'", body.url(), projectId, path);
            return ResponseEntity.ok(new ClipResponse(
                    stored.getPath(), linkBuilder.linkFor(stored, projectId)));
        } catch (IOException e) {
            throw new IllegalStateException("could not write clip '" + path + "'", e);
        }
    }

    /**
     * Send a back-channel signal about one entry.
     *
     * <p>`200` with the outcome when the source answered — including
     * `UNSUPPORTED`, which is a verdict and not an error. A source that is
     * unknown or gated raises, because that is our refusal and not the source's;
     * the exception handler below turns it into `409`.
     */
    @PostMapping("/brain/{tenant}/addon/centauri/signal")
    public SignalResponseView signal(@PathVariable String tenant,
                                     @RequestParam String projectId,
                                     @RequestBody SignalRequestView body,
                                     HttpServletRequest request) {
        // WRITE, not READ: a signal leaves the house. Reading a feed is not
        // permission to speak in the project's name to a foreign service.
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);
        FeedScope scope = scope(tenant, projectId, request);

        FeedSignal signal = parseEnum(FeedSignal.class, body.signal(), "signal");
        FeedSignalRequest signalRequest = new FeedSignalRequest(
                body.itemId(),
                signal,
                signal == FeedSignal.REPORT
                        ? parseEnum(FeedReportReason.class, body.reason(), "reason") : null,
                signal == FeedSignal.REQUEST
                        ? parseEnum(FeedRequestKind.class, body.requestKind(), "requestKind")
                        : null,
                body.note(),
                null);

        FeedSignalOutcome outcome =
                centauriService.sendSignal(body.sourceId(), signalRequest, scope);
        return new SignalResponseView(outcome.name());
    }

    /**
     * A refused signal or an unreadable request is the caller's problem, not a
     * server fault — and a 5xx here would invite the client to retry something
     * that cannot succeed.
     */
    @ExceptionHandler({CentauriException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> onRefused(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }

    private static <E extends Enum<E>> E parseEnum(
            Class<E> type, @Nullable String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + field + " '" + raw + "' — allowed: "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    // ── mapping ──────────────────────────────────────────────────────

    private static FeedCapabilitiesView toView(FeedCapabilities caps) {
        List<String> kinds = new ArrayList<>();
        for (FeedSelectorKind kind : caps.selectorKinds()) {
            kinds.add(kind.name());
        }
        List<String> signals = new ArrayList<>();
        for (FeedSignal signal : caps.signalsAccepted()) {
            signals.add(signal.name());
        }
        return new FeedCapabilitiesView(
                caps.selectorMode().name(), kinds,
                caps.pushdownTextSearch(), caps.pushdownLanguage(), caps.pushdownSince(),
                caps.supportsNewerDirection(), caps.carriesFullBody(), caps.maxPageSize(),
                signals, caps.carriesControlUrl());
    }

    private static FeedPageView toView(CentauriPage page) {
        List<FeedItemView> items = new ArrayList<>(page.items().size());
        for (CentauriItem entry : page.items()) {
            FeedItem item = entry.item();
            items.add(new FeedItemView(
                    item.id(), item.publishedAt().toString(), item.title(), item.url(),
                    item.summary(), item.author(), item.language(), item.imageUrl(),
                    item.controlUrl(), item.tags(),
                    entry.sourceId(), entry.sourceDisplayName(), entry.selector()));
        }
        List<FeedNoteView> notes = new ArrayList<>(page.notes().size());
        for (CentauriNote note : page.notes()) {
            notes.add(new FeedNoteView(note.sourceId(), note.selector(),
                    note.kind().name(), note.detail()));
        }
        return new FeedPageView(items, page.nextCursor(), page.hasMore(), notes,
                page.droppedByFilter(), page.droppedAsDuplicate());
    }

    private static FeedConfigView toView(String folder, @Nullable String title, FeedsConfig cfg) {
        List<FeedStreamView> streams = new ArrayList<>(cfg.streams().size());
        for (FeedStream stream : cfg.streams()) {
            streams.add(new FeedStreamView(stream.sourceId(), stream.selector()));
        }
        return new FeedConfigView(folder, title, streams,
                new FeedFilterView(cfg.text(), new ArrayList<>(cfg.languages()),
                        cfg.include(), cfg.exclude(), cfg.since()),
                cfg.pageSize());
    }

    private static FeedsConfig fromView(FeedConfigView view) {
        List<FeedStream> streams = new ArrayList<>();
        if (view.streams() != null) {
            for (FeedStreamView stream : view.streams()) {
                if (stream != null && stream.source() != null && !stream.source().isBlank()) {
                    streams.add(new FeedStream(stream.source(), stream.selector()));
                }
            }
        }
        FeedsConfig filter = fromView(view.filter());
        return new FeedsConfig(streams, filter.text(), filter.languages(),
                filter.include(), filter.exclude(), filter.since(), view.pageSize());
    }

    /** A filter-only config, so both call sites share the null handling. */
    private static FeedsConfig fromView(@Nullable FeedFilterView view) {
        if (view == null) {
            return FeedsConfig.empty();
        }
        Set<String> languages = view.languages() == null
                ? Set.of() : Set.copyOf(new LinkedHashSet<>(view.languages()));
        return new FeedsConfig(List.of(), view.text(), languages,
                view.include() == null ? List.of() : view.include(),
                view.exclude() == null ? List.of() : view.exclude(),
                view.since(), 0);
    }

    // ── internals ────────────────────────────────────────────────────

    private static String renderClip(ClipRequest body) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("title: ").append(yaml(body.title())).append('\n');
        sb.append("source_url: ").append(yaml(body.url())).append('\n');
        if (body.publishedAt() != null) {
            sb.append("published_at: ").append(yaml(body.publishedAt())).append('\n');
        }
        if (body.author() != null) {
            sb.append("author: ").append(yaml(body.author())).append('\n');
        }
        if (body.language() != null) {
            sb.append("language: ").append(yaml(body.language())).append('\n');
        }
        if (body.sourceId() != null) {
            sb.append("feed_source: ").append(yaml(body.sourceId())).append('\n');
        }
        sb.append("---\n\n# ").append(body.title()).append("\n\n");
        if (body.summary() != null && !body.summary().isBlank()) {
            sb.append(body.summary().trim()).append("\n\n");
        }
        if (body.body() != null && !body.body().isBlank()) {
            sb.append(body.body().trim()).append("\n\n");
        }
        sb.append("[").append(body.url()).append("](").append(body.url()).append(")\n");
        return sb.toString();
    }

    /** Quote defensively — a headline with a colon would otherwise break the block. */
    private static String yaml(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String normalisePath(String raw) {
        String path = raw == null ? "" : raw.trim();
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("targetPath is required");
        }
        return path.endsWith(".md") ? path : path + ".md";
    }

    private static String requireFolder(FeedPageRequest body) {
        if (body.folder() == null || body.folder().isBlank()) {
            throw new IllegalArgumentException(
                    "either folder or an explicit streams list is required");
        }
        return body.folder();
    }

    private static FeedDirection direction(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return FeedDirection.OLDER;
        }
        return "newer".equals(raw.trim().toLowerCase(Locale.ROOT))
                ? FeedDirection.NEWER : FeedDirection.OLDER;
    }

    private static FeedScope scope(String tenant, String projectId, HttpServletRequest request) {
        return new FeedScope(tenant, projectId, null, currentUser(request));
    }

    private static @Nullable String currentUser(HttpServletRequest req) {
        Object o = req.getAttribute("vanceUserId");
        return o instanceof String s ? s : null;
    }
}
