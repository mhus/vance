package de.mhus.vance.foot.ui;

import de.mhus.vance.api.followup.FollowUpRequestDto;
import de.mhus.vance.api.followup.FollowUpResponseDto;
import de.mhus.vance.api.followup.FollowUpSuggestionDto;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.BrainRestClientService;
import de.mhus.vance.foot.session.SessionService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>Caches one suggestion per {@code projectId + assistantContent} key.</li>
 *   <li>Once the user accepts a suggestion (Right-Arrow / Tab), it is not
 *       re-offered for the same assistant message.</li>
 *   <li>Errors are swallowed — the ghost text is a UX nicety, not a
 *       blocking feature.</li>
 * </ul>
 *
 * <p>The suggestion is fetched asynchronously on a background thread so
 * the input loop never blocks on the REST call. A fetch sequence number
 * guards against stale responses.
 */
@Service
public class FollowUpSuggestionService implements LiveRegion.IdleSuggestionProvider {

    private static final Logger log = LoggerFactory.getLogger(FollowUpSuggestionService.class);

    private final FootConfig config;
    private final ObjectProvider<BrainRestClientService> restProvider;
    private final SessionService sessions;

    /** Cache keyed on {@code projectId + "::" + assistantContent}. */
    private final Map<String, @Nullable String> cache = new ConcurrentHashMap<>();
    /** Keys for which the user already accepted the suggestion. */
    private final java.util.Set<String> accepted = ConcurrentHashMap.newKeySet();

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
            accepted.add(key);
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
     * Triggers an asynchronous fetch of a follow-up suggestion from
     * the brain. Called by {@link LiveRegion}'s idle timer when the
     * input is empty and the user has been idle for the configured
     * delay. The fetch runs on the calling thread (the animator
     * thread) — the REST call has a short timeout and the animator
     * is a daemon thread, so a brief block is acceptable.
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

        String key = projectId + "::" + assistant;
        if (accepted.contains(key)) {
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

        // Fire the REST call synchronously — the animator thread can
        // spare a few hundred ms. If it times out the suggestion just
        // stays null.
        BrainRestClientService rest = restProvider.getIfAvailable();
        if (rest == null) {
            log.warn("fetchIfApplicable: BrainRestClientService not available — cannot fetch");
            return;
        }
        int seq = fetchSeq.incrementAndGet();
        log.trace("fetchIfApplicable: firing REST call — project={}, contentLen={}, seq={}",
                projectId, assistant.length(), seq);
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
            log.warn("fetchIfApplicable: REST call failed — {}: {}", e.getClass().getSimpleName(), e.getMessage());
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
}
