package de.mhus.vance.brain.centauri;

import de.mhus.vance.brain.agrajag.AgrajagChecker;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.feed.FeedActor;
import de.mhus.vance.toolpack.feed.FeedCapabilities;
import de.mhus.vance.toolpack.feed.FeedFetch;
import de.mhus.vance.toolpack.feed.FeedFilter;
import de.mhus.vance.toolpack.feed.FeedPage;
import de.mhus.vance.toolpack.feed.FeedScope;
import de.mhus.vance.toolpack.feed.FeedSourceInstance;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
     * Per-stream budget. A mixed page waits for its slowest source, so this
     * is the ceiling on how long one bad source may hold up the others.
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
            planned.add(new Planned(stream, instance, capabilities.get(instance)));
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
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FeedPage>> futures = new ArrayList<>(planned.size());
            for (Planned p : planned) {
                futures.add(pool.submit(() -> fetchOne(p, request, scope, incoming)));
            }
            for (int i = 0; i < planned.size(); i++) {
                Planned p = planned.get(i);
                try {
                    FeedPage page = futures.get(i)
                            .get(STREAM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    out.add(new FeedMerger.StreamFetch(p.stream(), p.instance(), page));
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

    private FeedPage fetchOne(
            Planned p, CentauriPageRequest request, FeedScope scope, CentauriCursor incoming) {
        FeedFilter filter = request.filter();
        FeedCapabilities caps = p.capabilities();
        int limit = FeedMerger.fetchLimit(
                request.pageSize(), filter.needsPostFilter(caps), caps.maxPageSize());
        @Nullable FeedActor actor = actorResolver.resolve(scope, p.instance().id());
        FeedFetch fetch = new FeedFetch(
                p.stream().selector(),
                incoming.cursorFor(p.stream()),
                request.direction(),
                limit,
                filter.projectTo(caps),
                actor);
        return p.instance().fetch(fetch);
    }

    /**
     * Hand a hard failure to the failure tracker, which classifies it and may
     * set a cooldown the gate will see on the next page. Failing to report
     * must not turn into a second failure.
     */
    private void reportFailure(Planned p, FeedScope scope, Throwable cause) {
        log.warn("Centauri: source '{}' selector '{}' failed: {}",
                p.instance().id(), p.stream().selector(), cause.toString());
        try {
            agrajagChecker.handle(
                    CentauriSettings.cooldownSubject(p.instance().id()),
                    cause,
                    new ToolInvocationContext(
                            scope.tenantId(), scope.projectId(), null,
                            scope.processId(), scope.userId()));
        } catch (RuntimeException e) {
            log.warn("Centauri: failure triage for '{}' raised: {}",
                    p.instance().id(), e.toString());
        }
    }

    /** A stream that survived resolution and gating, with its capabilities. */
    private record Planned(
            FeedStream stream,
            FeedSourceInstance instance,
            FeedCapabilities capabilities) { }
}
