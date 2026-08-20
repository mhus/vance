package de.mhus.vance.brain.centauri;

import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedItem;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSignalOutcome;
import de.mhus.vance.toolpack.feed.FeedSignalRequest;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The one entry point for reading a mixed feed.
 *
 * <p>Resolves the configured sources of a project, gates them, fetches one
 * page per stream concurrently, and hands the results to {@link FeedMerger}.
 * Single-shot like {@code ZarniwoopService} — no think-process, no lane lock,
 * no state between pages beyond the cursor the client carries.
 *
 * <p><b>One failing stream does not fail the page.</b> A mixed feed with five
 * sources would otherwise be as available as the least available of them. A
 * stream that is off, cooling down, failing or too slow becomes a
 * {@link CentauriNote} and the remaining streams still render.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentauriService {

    /**
     * Budget for the <b>whole</b> fan-out, not per stream.
     *
     * <p>It has to be one shared deadline rather than a per-future timeout: the
     * futures are awaited in sequence, so {@code get(15s)} in a loop over five
     * dead sources spends seventy-five seconds — each wait starting only when
     * the one before it gave up. A page someone is scrolling gets fifteen
     * seconds in total, and whatever answered inside it is what renders.
     */
    static final Duration STREAM_TIMEOUT = Duration.ofSeconds(15);

    private final FeedSourceFactory factory;
    private final FeedCapabilitiesCache capabilities;
    private final CentauriGateService gate;
    private final FeedActorResolver actorResolver;
    private final CentauriCursorCodec cursorCodec;
    private final AgrajagChecker agrajagChecker;
    private final MetricService metrics;

    public CentauriPage fetchPage(CentauriPageRequest request, FeedScope scope) {
        if (scope == null || StringUtils.isBlank(scope.projectId())) {
            throw new CentauriException("feeds require a project scope");
        }
        if (request.streams().isEmpty()) {
            return CentauriPage.empty(List.of());
        }

        CentauriCursor incoming = cursorCodec.decode(request.cursor());
        List<CentauriNote> notes = new ArrayList<>();
        List<Planned> planned = new ArrayList<>();

        for (FeedStream stream : request.streams()) {
            if (incoming.isExhausted(stream)) {
                continue;
            }
            FeedSourceInstance instance = factory.find(scope, stream.sourceId());
            if (instance == null) {
                notes.add(new CentauriNote(stream.sourceId(), stream.selector(),
                        CentauriNote.Kind.UNKNOWN_SOURCE, null));
                continue;
            }
            var blocked = gate.check(scope, instance.id());
            if (blocked.isPresent()) {
                notes.add(new CentauriNote(stream.sourceId(), stream.selector(),
                        blocked.get() == CentauriGateService.Blocked.DISABLED
                                ? CentauriNote.Kind.DISABLED
                                : CentauriNote.Kind.COOLING_DOWN,
                        null));
                continue;
            }
            // Capabilities are resolved inside the fetch task, not here: for an
            // HTTP-backed source this is a network call, and on the request
            // thread it would sit outside every timeout in this method.
            planned.add(new Planned(stream, instance));
        }

        if (planned.isEmpty()) {
            return CentauriPage.empty(notes);
        }

        List<FeedMerger.StreamFetch> fetches = fetchAll(planned, request, scope, incoming, notes);
        if (fetches.isEmpty()) {
            return CentauriPage.empty(notes);
        }

        FeedMerger.MergeResult merged = FeedMerger.merge(
                fetches, request.filter(), request.pageSize(), request.direction(), incoming);

        return new CentauriPage(
                merged.items(),
                cursorCodec.encode(merged.cursor()),
                merged.hasMore(),
                notes,
                merged.droppedByFilter(),
                merged.droppedAsDuplicate());
    }

    /**
     * Send one back-channel signal about one entry (§12a of the plan).
     *
     * <p>Three refusals, and they are deliberately different things:
     * <ul>
     *   <li>An unknown or gated source is <b>our</b> decision, not the source's —
     *       that raises {@link CentauriException} rather than returning an
     *       outcome, because "we did not send" is not a verdict the source gave.
     *   <li>A signal the source did not declare returns
     *       {@link FeedSignalOutcome#UNSUPPORTED} without a call. The UI should
     *       not have offered it; this is the second line, not the gate.
     *   <li>A transport failure goes to the failure tracker like a failed fetch
     *       and then surfaces as a {@link CentauriException}.
     * </ul>
     *
     * <p>Nothing is recorded locally. What the source does with a signal is its
     * business, so there is no state here to reconcile and nothing to retry —
     * a lost signal costs one click.
     */
    public FeedSignalOutcome sendSignal(
            String sourceId, FeedSignalRequest request, FeedScope scope) {
        if (scope == null || StringUtils.isBlank(scope.projectId())) {
            throw new CentauriException("feeds require a project scope");
        }
        FeedSourceInstance instance = factory.find(scope, sourceId);
        if (instance == null) {
            throw new CentauriException("unknown feed source '" + sourceId + "'");
        }
        var blocked = gate.check(scope, sourceId);
        if (blocked.isPresent()) {
            throw new CentauriException("source '" + sourceId + "' is "
                    + blocked.get().name().toLowerCase(java.util.Locale.ROOT)
                    + " — nothing was sent");
        }

        FeedCapabilities caps = capabilities.get(scope, instance);
        if (!caps.accepts(request.signal())) {
            metrics.counter("vance.centauri.signal",
                    "source", sourceId, "outcome", "unsupported").increment();
            return FeedSignalOutcome.UNSUPPORTED;
        }

        FeedSignalRequest withActor = new FeedSignalRequest(
                request.itemId(), request.signal(), request.reason(), request.requestKind(),
                request.note(), actorResolver.resolve(scope, sourceId));

        FeedSignalOutcome outcome;
        try {
            outcome = instance.sendSignal(withActor);
        } catch (RuntimeException e) {
            reportFailure(instance, scope, e);
            throw new CentauriException(
                    "source '" + sourceId + "' refused the signal: " + e.getMessage(), e);
        }
        metrics.counter("vance.centauri.signal", "source", sourceId,
                "outcome", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
        log.info("Centauri: signal {} on '{}' item '{}' → {}",
                request.signal(), sourceId, request.itemId(), outcome);
        return outcome;
    }

    /**
     * One entry in full — the same record the page carries, with whatever the
     * listing left out.
     *
     * <p>Empty rather than an error for an id the source does not know: an
     * entry can age out between the page and the click, and that is the
     * source's business, not a failure.
     *
     * <p>Gated and reported like a fetch — an endpoint that is off, cooling
     * down or broken must not be reachable through a second door.
     */
    public Optional<FeedItem> loadItem(String sourceId, String itemId, FeedScope scope) {
        if (scope == null || StringUtils.isBlank(scope.projectId())) {
            throw new CentauriException("feeds require a project scope");
        }
        FeedSourceInstance instance = factory.find(scope, sourceId);
        if (instance == null) {
            throw new CentauriException("unknown feed source '" + sourceId + "'");
        }
        if (gate.check(scope, sourceId).isPresent()) {
            throw new CentauriException("feed source '" + sourceId + "' is not available");
        }
        try {
            Optional<FeedItem> item = instance.loadItem(
                    itemId, actorResolver.resolve(scope, sourceId));
            metrics.counter("vance.centauri.item", "source", sourceId,
                    "outcome", item.isPresent() ? "found" : "unknown").increment();
            return item;
        } catch (RuntimeException e) {
            reportFailure(instance, scope, e);
            metrics.counter("vance.centauri.item", "source", sourceId,
                    "outcome", "failed").increment();
            throw new CentauriException(
                    "source '" + sourceId + "' could not serve the entry: " + e.getMessage(), e);
        }
    }

    // ── internals ────────────────────────────────────────────────────

    /**
     * Fetch every planned stream concurrently. Virtual threads because this is
     * pure IO fan-out and the count follows the reader's configuration rather
     * than any pool size we could pick sensibly.
     */
    private List<FeedMerger.StreamFetch> fetchAll(
            List<Planned> planned, CentauriPageRequest request, FeedScope scope,
            CentauriCursor incoming, List<CentauriNote> notes) {

        List<FeedMerger.StreamFetch> out = new ArrayList<>(planned.size());
        // One deadline for the whole fan-out. Awaiting the futures in sequence
        // with a per-future timeout would multiply the budget by the number of
        // dead sources; the remaining budget is what each wait actually gets.
        long deadlineNanos = System.nanoTime() + STREAM_TIMEOUT.toNanos();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Fetched>> futures = new ArrayList<>(planned.size());
            for (Planned p : planned) {
                futures.add(pool.submit(() -> fetchOne(p, request, scope, incoming)));
            }
            for (int i = 0; i < planned.size(); i++) {
                Planned p = planned.get(i);
                try {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new TimeoutException("shared fetch deadline spent");
                    }
                    Fetched fetched = futures.get(i)
                            .get(remainingNanos, TimeUnit.NANOSECONDS);
                    if (fetched.page() == null) {
                        notes.add(new CentauriNote(p.stream().sourceId(), p.stream().selector(),
                                CentauriNote.Kind.MISSING_FACET,
                                String.join(", ", fetched.missingFacets())));
                        metrics.counter("vance.centauri.fetch",
                                "source", p.instance().id(), "outcome", "missing_facet")
                                .increment();
                        continue;
                    }
                    out.add(new FeedMerger.StreamFetch(
                            p.stream(), p.instance(), fetched.page(), fetched.pushdown()));
                    metrics.counter("vance.centauri.fetch",
                            "source", p.instance().id(), "outcome", "success").increment();
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    notes.add(new CentauriNote(p.stream().sourceId(), p.stream().selector(),
                            CentauriNote.Kind.TIMED_OUT, null));
                    metrics.counter("vance.centauri.fetch",
                            "source", p.instance().id(), "outcome", "timeout").increment();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    reportFailure(p, scope, cause);
                    notes.add(new CentauriNote(p.stream().sourceId(), p.stream().selector(),
                            CentauriNote.Kind.FAILED, cause.getMessage()));
                    metrics.counter("vance.centauri.fetch",
                            "source", p.instance().id(), "outcome", "failed").increment();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CentauriException("feed fetch interrupted", e);
                }
            }
        }
        return out;
    }

    private Fetched fetchOne(
            Planned p, CentauriPageRequest request, FeedScope scope, CentauriCursor incoming) {
        FeedFilter filter = request.filter();
        FeedCapabilities caps = capabilities.get(scope, p.instance());
        List<String> missing = filter.undeclaredFacets(caps);
        if (!missing.isEmpty()) {
            // Not filtered locally and not silently ignored: an entry carries
            // no facet values to check, and letting the stream through would
            // make the filter look broken rather than strict.
            return Fetched.skipped(missing);
        }
        int limit = FeedMerger.fetchLimit(
                request.pageSize(), filter.needsPostFilter(caps), caps.maxPageSize());
        @Nullable FeedActor actor = actorResolver.resolve(scope, p.instance().id());
        FeedFilter pushdown = filter.projectTo(caps);
        FeedFetch fetch = new FeedFetch(
                p.stream().selector(),
                incoming.cursorFor(p.stream()),
                request.direction(),
                limit,
                pushdown,
                actor);
        // The pushdown travels back out: the merge has to know what this source
        // already answered, or it re-checks a text match against text the
        // source never delivered and drops hits it found correctly.
        return Fetched.of(p.instance().fetch(fetch), pushdown);
    }

    /**
     * Hand a hard failure to the failure tracker, which classifies it and may
     * set a cooldown the gate will see on the next page. Failing to report
     * must not turn into a second failure.
     */
    private void reportFailure(Planned p, FeedScope scope, Throwable cause) {
        log.warn("Centauri: source '{}' selector '{}' failed: {}",
                p.instance().id(), p.stream().selector(), cause.toString());
        reportFailure(p.instance(), scope, cause);
    }

    /**
     * Hand a hard failure to the failure tracker, which classifies it and may set
     * a cooldown the gate will see next time. Failing to report must not turn
     * into a second failure.
     */
    private void reportFailure(FeedSourceInstance instance, FeedScope scope, Throwable cause) {
        try {
            agrajagChecker.handle(
                    CentauriSettings.cooldownSubject(instance.id()),
                    cause,
                    new ToolInvocationContext(
                            scope.tenantId(), scope.projectId(), null,
                            scope.processId(), scope.userId()));
        } catch (RuntimeException e) {
            log.warn("Centauri: failure triage for '{}' raised: {}",
                    instance.id(), e.toString());
        }
    }

    /** A stream that survived resolution and gating. */
    private record Planned(
            FeedStream stream,
            FeedSourceInstance instance) { }

    /**
     * What one fetch task produced, plus what it had delegated to the source.
     *
     * <p>{@code missingFacets} non-empty means nothing was fetched at all:
     * the reader selected a dimension this source never declared, so it was
     * left out instead of being asked a question it cannot answer. Carried
     * back as a value rather than thrown, because it is not a failure of the
     * source and must not reach the failure tracker or a cooldown.
     */
    private record Fetched(
            @Nullable FeedPage page,
            FeedFilter pushdown,
            List<String> missingFacets) {

        static Fetched of(FeedPage page, FeedFilter pushdown) {
            return new Fetched(page, pushdown, List.of());
        }

        static Fetched skipped(List<String> missingFacets) {
            return new Fetched(null, FeedFilter.none(), missingFacets);
        }
    }
}
