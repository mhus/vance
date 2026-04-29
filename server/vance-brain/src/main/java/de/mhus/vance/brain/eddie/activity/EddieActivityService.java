package de.mhus.vance.brain.eddie.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Append-and-recap façade over {@link EddieActivityRepository}. The
 * Vance hub engine writes an entry whenever it has done something
 * peers might want to know about; on {@code start} / {@code resume}
 * each Vance process pulls a recent slice via
 * {@link #readPeerRecap(String, String, String)} to greet the user
 * with awareness of what other hubs have been up to.
 *
 * <p>"Nothing is ever deleted" — see
 * {@code specification/vance-engine.md} §9 #5. The default-3-day
 * window is a *read* filter, not a TTL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EddieActivityService {

    /** Default look-back for {@code readPeerRecap} when no override is given. */
    public static final Duration DEFAULT_RECAP_WINDOW = Duration.ofDays(3);

    /** Default cap on the number of recap rows returned. */
    public static final int DEFAULT_RECAP_LIMIT = 50;

    private final EddieActivityRepository repository;

    /**
     * Persists an Activity-Log entry. Convenience for the hot-path
     * — fills in {@code ts = now()} and an empty refs list when not
     * provided. Returns the saved row.
     */
    public EddieActivityEntry append(
            String tenantId,
            String userId,
            String sessionId,
            String vanceProcessId,
            EddieActivityKind kind,
            String summary,
            List<EntityRef> refs) {
        EddieActivityEntry entry = EddieActivityEntry.builder()
                .tenantId(tenantId)
                .userId(userId)
                .sessionId(sessionId)
                .vanceProcessId(vanceProcessId)
                .ts(Instant.now())
                .kind(kind)
                .summary(summary == null ? "" : summary)
                .refs(refs == null ? List.of() : refs)
                .build();
        EddieActivityEntry saved = repository.save(entry);
        log.debug("Activity append tenant='{}' user='{}' kind={} summary='{}'",
                tenantId, userId, kind, abbreviate(summary, 80));
        return saved;
    }

    /**
     * Reads recent peer activity for the calling Vance process —
     * "everything other hub-sessions of the same user did in the
     * last 3 days". Newest-first, capped at {@link #DEFAULT_RECAP_LIMIT}.
     */
    public List<EddieActivityEntry> readPeerRecap(
            String tenantId, String userId, String vanceProcessId) {
        return readPeerRecap(tenantId, userId, vanceProcessId,
                DEFAULT_RECAP_WINDOW, DEFAULT_RECAP_LIMIT);
    }

    /**
     * Same as {@link #readPeerRecap(String, String, String)} but with
     * caller-controlled window and limit.
     */
    public List<EddieActivityEntry> readPeerRecap(
            String tenantId,
            String userId,
            String vanceProcessId,
            Duration window,
            int limit) {
        Instant since = Instant.now().minus(window);
        Pageable page = PageRequest.of(0, Math.max(1, limit));
        return repository.findPeerActivity(
                tenantId, userId, vanceProcessId, since, page);
    }

    private static String abbreviate(@org.jspecify.annotations.Nullable String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
