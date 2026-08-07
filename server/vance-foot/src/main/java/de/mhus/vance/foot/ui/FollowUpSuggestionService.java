package de.mhus.vance.foot.ui;

import de.mhus.vance.api.followup.FollowUpRequestDto;
import de.mhus.vance.api.followup.FollowUpResponseDto;
import de.mhus.vance.api.followup.FollowUpSuggestionDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.session.SessionService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Fetches idle-triggered follow-up suggestions from the brain's
 * {@code POST /brain/{tenant}/follow-up/{project}} endpoint (reply mode)
 * and exposes them to {@link LiveRegion} as ghost text.
 *
 * <p>Mirrors the web UI's {@code useFollowUpSuggestion} composable:
 * <ul>
 *   <li>Caches one suggestion per {@code projectId + assistantContent} key
 *       (bounded LRU, keyed on a digest — the text itself is not kept).</li>
 *   <li>Once the user accepts a suggestion (Right-Arrow / Tab), it is not
 *       re-offered for the same assistant message.</li>
 *   <li>Errors are swallowed — the ghost text is a UX nicety, not a
 *       blocking feature.</li>
 * </ul>
 *
 * <p>The REST call runs on a dedicated single daemon thread, never on
 * {@link LiveRegion}'s animator: the endpoint is LLM-backed and can take
 * seconds, and the animator is what redraws the live region. A fetch
 * sequence number guards against stale responses.
 */
@Service
public class FollowUpSuggestionService implements LiveRegion.IdleSuggestionProvider {

    private static final Logger log = LoggerFactory.getLogger(FollowUpSuggestionService.class);

    private final FootConfig config;
    private final ObjectProvider<BrainRestClientService> restProvider;
    private final SessionService sessions;

    /**
     * Entries kept in each of the two stores. foot is a long-lived
     * process and a busy session produces hundreds of assistant
     * messages, so both maps are bounded — an unbounded cache keyed on
     * message *content* would hold every reply of the session forever.
     */
    private static final int CACHE_MAX = 200;

    /**
     * Cache keyed on a digest of {@code projectId + assistantContent}
     * (see {@link #cacheKey}). Bounded LRU — the value is a one-line
     * suggestion, but the key must not be the whole assistant message.
     */
    private final Map<String, @Nullable String> cache = boundedLru(CACHE_MAX);
    /** Keys for which the user already accepted the suggestion. Bounded LRU. */
    private final Map<String, Boolean> accepted = boundedLru(CACHE_MAX);

    /** Last assistant message content — set by ChatMessageAppendedHandler. */
    private final AtomicReference<@Nullable String> lastAssistantContent = new AtomicReference<>(null);

    /** Monotonic sequence to drop stale fetch responses. */
    private final AtomicInteger fetchSeq = new AtomicInteger(0);
    /** Bumped whenever the fetch inputs change — re-arms the idle trigger. */
    private final AtomicLong stateGeneration = new AtomicLong(0);
    /** The currently visible suggestion (volatile — read from the animator thread). */
    private volatile @Nullable String currentSuggestion = null;
    /** The cache key that produced {@link #currentSuggestion}. */
    private volatile @Nullable String currentKey = null;

    /**
     * Carries the REST round-trip off the animator thread. Single-threaded
     * and daemon: overlapping fetches serialise instead of piling up, and
     * an in-flight suggestion never keeps foot from exiting.
     */
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vance-foot-followup");
        t.setDaemon(true);
        return t;
    });

    public FollowUpSuggestionService(FootConfig config,
                                     ObjectProvider<BrainRestClientService> restProvider,
                                     SessionService sessions) {
        this.config = config;
        this.restProvider = restProvider;
        this.sessions = sessions;
        log.info("FollowUpSuggestionService instantiated — enabled={}, idleDelay={}ms",
                config.getUi().getFollowUp().isEnabled(),
                config.getUi().getFollowUp().getIdleDelay().toMillis());
    }

    /**
     * Called by {@code ChatMessageAppendedHandler} whenever a new
     * assistant message is committed. Clears the current suggestion and
     * bumps {@link #stateGeneration()} so the next idle period fetches
     * fresh even if the user hasn't touched the keyboard since.
     */
    public void onAssistantMessage(@Nullable String content) {
        log.trace("onAssistantMessage called — content length={}, blank={}",
                content == null ? 0 : content.length(),
                content == null || content.isBlank());
        lastAssistantContent.set(content);
        currentSuggestion = null;
        currentKey = null;
        stateGeneration.incrementAndGet();
    }

    // ─── IdleSuggestionProvider ────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return config.getUi().getFollowUp().isEnabled();
    }

    @Override
    public long idleDelayMs() {
        return config.getUi().getFollowUp().getIdleDelay().toMillis();
    }

    @Override
    public @Nullable String currentSuggestion() {
        if (!isEnabled()) return null;
        return currentSuggestion;
    }

    @Override
    public void acceptCurrent() {
        String key = currentKey;
        if (key != null) {
            accepted.put(key, Boolean.TRUE);
        }
        currentSuggestion = null;
    }

    @Override
    public void clearSuggestion() {
        currentSuggestion = null;
    }

    @Override
    public long stateGeneration() {
        return stateGeneration.get();
    }

    /**
     * Triggers a fetch of a follow-up suggestion from the brain. Called
     * by {@link LiveRegion}'s idle timer when the input is empty and the
     * user has been idle for the configured delay.
     *
     * <p>Everything up to and including the cache lookup runs on the
     * calling (animator) thread — those are map reads. The REST call
     * itself is handed to {@link #fetcher}: the endpoint is backed by an
     * LLM (recipe {@code follow-up}), not a cheap GET, so it can take
     * seconds, and the animator thread is what redraws the live region.
     * The suggestion is picked up by a later tick via
     * {@link #currentSuggestion()}.
     */
    @Override
    public void fetchIfApplicable() {
        if (!isEnabled()) {
            log.trace("fetchIfApplicable: feature disabled");
            return;
        }
        String assistant = lastAssistantContent.get();
        if (assistant == null || assistant.isBlank()) {
            log.trace("fetchIfApplicable: no last assistant content — skipping");
            return;
        }

        SessionService.BoundSession bound = sessions.current();
        if (bound == null) {
            log.trace("fetchIfApplicable: no bound session — skipping");
            return;
        }
        String projectId = bound.projectId();
        if (projectId == null || projectId.isBlank()) {
            log.trace("fetchIfApplicable: no projectId in bound session — skipping");
            return;
        }

        String key = cacheKey(projectId, assistant);
        if (accepted.containsKey(key)) {
            log.trace("fetchIfApplicable: suggestion already accepted for this key — skipping");
            return;
        }

        // Cache hit?
        if (cache.containsKey(key)) {
            String cached = cache.get(key);
            log.trace("fetchIfApplicable: cache hit — cached={}", cached == null ? "null" : "'" + (cached.length() > 60 ? cached.substring(0, 60) + "…" : cached) + "'");
            currentSuggestion = cached;
            currentKey = key;
            return;
        }

        BrainRestClientService rest = restProvider.getIfAvailable();
        if (rest == null) {
            log.trace("fetchIfApplicable: BrainRestClientService not available — cannot fetch");
            return;
        }
        int seq = fetchSeq.incrementAndGet();
        log.trace("fetchIfApplicable: firing REST call — project={}, contentLen={}, seq={}",
                projectId, assistant.length(), seq);
        // Off the animator thread — see the method Javadoc. The
        // single-threaded executor also serialises overlapping fetches,
        // so the seq guard only has to discard stale *results*.
        fetcher.execute(() -> runFetch(rest, key, projectId, assistant, seq));
    }

    /** The REST round-trip itself. Runs on {@link #fetcher}, never on the animator. */
    private void runFetch(
            BrainRestClientService rest, String key, String projectId,
            String assistant, int seq) {
        try {
            FollowUpRequestDto body = FollowUpRequestDto.builder()
                    .text(assistant)
                    .count(1)
                    .mode("chat-reply")
                    .build();
            String path = "/brain/" + config.getAuth().getTenant()
                    + "/follow-up/" + urlEncode(projectId);
            FollowUpResponseDto resp = rest.post(path, body, FollowUpResponseDto.class);
            // Stale check — a newer fetch may have superseded us.
            if (seq != fetchSeq.get()) {
                log.trace("fetchIfApplicable: stale response (seq={}, current={}) — discarding",
                        seq, fetchSeq.get());
                return;
            }
            List<FollowUpSuggestionDto> suggestions = resp.getSuggestions();
            String text = null;
            if (suggestions != null && !suggestions.isEmpty()) {
                String first = suggestions.get(0).getText();
                if (first != null) {
                    first = first.trim();
                    text = first.isEmpty() ? null : first;
                }
            }
            cache.put(key, text);
            currentSuggestion = text;
            currentKey = key;
            log.trace("fetchIfApplicable: success — suggestion={}",
                    text == null ? "null" : "'" + (text.length() > 60 ? text.substring(0, 60) + "…" : text) + "'");
        } catch (Exception e) {
            // Expected on an unreachable brain, and this is a UX nicety —
            // warning here would spam the log once per idle period.
            log.trace("fetchIfApplicable: REST call failed — {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            if (seq == fetchSeq.get()) {
                // Do NOT cache the failure — otherwise the first transient
                // error (e.g. JWT not yet minted, brain briefly unreachable)
                // permanently blocks the suggestion for this assistant
                // message. Leave the key absent so the next arming of the
                // idle trigger (user input, or a new assistant message)
                // retries. Not the next animator tick: the trigger is
                // latched per idle period, which is what keeps a failing
                // brain from being retried several times a second.
                currentSuggestion = null;
                currentKey = null;
            }
        }
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Cache key for one (project, assistant message) pair — a SHA-256
     * digest rather than the concatenation. The map is long-lived and the
     * assistant message can be kilobytes; only equality matters here, so
     * there is no reason to retain the text itself.
     */
    private static String cacheKey(String projectId, String assistant) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(projectId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(assistant.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // Platform-mandated; degrade to the plain key rather than
            // losing the feature.
            return projectId + "::" + assistant;
        }
    }

    /** Access-ordered map that evicts its eldest entry past {@code max}. */
    private static <V> Map<String, V> boundedLru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }
}
