package de.mhus.vance.foot.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.jspecify.annotations.Nullable;

/**
 * Contents of {@code .vancetope/session.yaml} — the session history anchor for
 * a working directory. Written whenever foot bootstraps into a session and
 * read back by {@code -c} / {@code --continue} to resume exactly that session.
 *
 * <p>Per directory (= per project, like the sibling {@code project.eddie.yaml} /
 * {@code access.yaml}). It holds no secret — just the ids of sessions entered
 * from here — so it is not treated as a credential.
 *
 * <p>Stores up to {@link #MAX_ENTRIES} session entries, newest first
 * (by {@code updatedAt} descending). This allows multiple concurrent
 * terminal windows in the same directory to each persist their session
 * without overwriting each other. {@code -c} picks the newest entry;
 * {@code -c --name=<n>} filters by name.
 *
 * <h2>Backward compatibility</h2>
 * The legacy format had a single {@code sessionId} / {@code projectId} /
 * {@code updatedAt} at the top level. On load, if {@code sessions} is
 * absent but the legacy {@code sessionId} is present, the store migrates
 * it into a single-entry {@code sessions} list. New writes always use
 * the {@code sessions} list; the legacy fields are left null so
 * {@code @JsonInclude(NON_NULL)} omits them.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionAnchor {

    /** Maximum number of session entries kept in the history. */
    public static final int MAX_ENTRIES = 20;

    // ── Legacy fields — kept for backward-compat migration on read only ──
    // Not written when the sessions list is populated (NON_NULL omits nulls).
    /** @deprecated migrated into {@link #sessions} on load. */
    @Deprecated(since = "session-history", forRemoval = false)
    private @Nullable String sessionId;

    /** @deprecated migrated into {@link #sessions} on load. */
    @Deprecated(since = "session-history", forRemoval = false)
    private @Nullable String projectId;

    /** @deprecated migrated into {@link #sessions} on load. */
    @Deprecated(since = "session-history", forRemoval = false)
    private @Nullable Long updatedAt;
    // ── End legacy fields ──

    /**
     * Ordered list of session entries, newest first. May be null when the
     * file hasn't been migrated yet (legacy single-session format).
     */
    private @Nullable List<SessionEntry> sessions;

    /**
     * A single session entry in the history.
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SessionEntry {

        /** Id of the session. */
        private @Nullable String sessionId;

        /** Project the session belongs to — informational. */
        private @Nullable String projectId;

        /** Client name ({@code --name}) if set, otherwise null. */
        private @Nullable String name;

        /** Unix-millis of the last update. */
        private @Nullable Long updatedAt;

        public SessionEntry() {}

        public SessionEntry(@Nullable String sessionId,
                            @Nullable String projectId,
                            @Nullable String name,
                            @Nullable Long updatedAt) {
            this.sessionId = sessionId;
            this.projectId = projectId;
            this.name = name;
            this.updatedAt = updatedAt;
        }
    }

    // ── Convenience accessors ──

    /**
     * Returns the sessions list, never null. If null, returns an empty
     * mutable list (does not set the field).
     */
    public List<SessionEntry> sessionsOrEmpty() {
        return sessions != null ? sessions : new ArrayList<>();
    }
}
