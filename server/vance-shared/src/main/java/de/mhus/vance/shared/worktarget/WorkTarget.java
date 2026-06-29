package de.mhus.vance.shared.worktarget;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Identifies the file/exec backend a worker process currently
 * operates against. Persisted as a Map under
 * {@code ThinkProcessDocument.engineParams[KEY]} so the standard
 * recipe-param-copy mechanism at spawn time carries it through
 * without schema changes.
 *
 * <p>Use {@link #toMap()} / {@link #fromMap(Map)} to round-trip
 * through {@code engineParams}.
 *
 * <p>{@code targetName} is a kind-dependent qualifier:
 * <ul>
 *   <li>{@link WorkTargetKind#CLIENT} — ignored. Foot operates
 *       against its own configured {@code --workdir}, not against a
 *       Brain-side RootDir.</li>
 *   <li>{@link WorkTargetKind#WORK} — the RootDir name; {@code null}
 *       resolves to the process's lazy temp RootDir at dispatch time.</li>
 *   <li>{@link WorkTargetKind#DAEMON} — the (required) name of a
 *       {@code profile=daemon} Foot registered in the same project.</li>
 * </ul>
 */
public record WorkTarget(WorkTargetKind kind, @Nullable String targetName) {

    /** {@code engineParams} key under which the persisted Map lives. */
    public static final String KEY = "workTarget";

    /** Sub-key for {@link #kind()} in the persisted Map. */
    public static final String FIELD_KIND = "kind";

    /** Sub-key for {@link #targetName()} in the persisted Map. */
    public static final String FIELD_TARGET_NAME = "targetName";

    /**
     * Legacy sub-key (pre-{@code targetName} rename, when WORK was the
     * only qualifier-bearing kind). Still read by {@link #fromMap(Map)}
     * so existing {@code engineParams} keep resolving; never written.
     */
    static final String LEGACY_FIELD_DIR_NAME = "dirName";

    public WorkTarget {
        if (kind == null) {
            throw new IllegalArgumentException("WorkTarget.kind is required");
        }
        if (kind == WorkTargetKind.DAEMON && (targetName == null || targetName.isBlank())) {
            throw new IllegalArgumentException(
                    "WorkTarget.targetName (the daemon name) is required for kind=DAEMON");
        }
    }

    /** Shortcut for the user-local Foot-CLI surface bound to the session. */
    public static WorkTarget client() {
        return new WorkTarget(WorkTargetKind.CLIENT, null);
    }

    /**
     * Brain-server workspace RootDir surface. {@code dirName == null}
     * resolves to the process's lazy temp RootDir at dispatch time.
     */
    public static WorkTarget work(@Nullable String dirName) {
        return new WorkTarget(WorkTargetKind.WORK, dirName);
    }

    /**
     * Named {@code profile=daemon} Foot in the same project. Routes the
     * {@code client_*} backend tools over the daemon's WebSocket.
     */
    public static WorkTarget daemon(String daemonName) {
        return new WorkTarget(WorkTargetKind.DAEMON, daemonName);
    }

    /** Round-trips into the form persisted on {@code engineParams[KEY]}. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FIELD_KIND, kind.name());
        if (targetName != null && !targetName.isBlank()) {
            out.put(FIELD_TARGET_NAME, targetName);
        }
        return out;
    }

    /**
     * Inverse of {@link #toMap()}. Tolerates legacy lowercase /
     * mixed-case {@code kind} strings and the pre-rename
     * {@code dirName} sub-key; throws {@link IllegalArgumentException}
     * on missing or unparseable input so a malformed recipe /
     * engineParams surfaces cleanly.
     */
    public static WorkTarget fromMap(@Nullable Map<String, Object> raw) {
        if (raw == null) {
            throw new IllegalArgumentException("WorkTarget map is null");
        }
        Object rawKind = raw.get(FIELD_KIND);
        if (!(rawKind instanceof String kindStr) || kindStr.isBlank()) {
            throw new IllegalArgumentException(
                    "WorkTarget map missing '" + FIELD_KIND + "'");
        }
        WorkTargetKind k;
        try {
            k = WorkTargetKind.valueOf(kindStr.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown WorkTargetKind: '" + kindStr + "'", ex);
        }
        Object rawName = raw.containsKey(FIELD_TARGET_NAME)
                ? raw.get(FIELD_TARGET_NAME)
                : raw.get(LEGACY_FIELD_DIR_NAME);
        String name = rawName instanceof String s && !s.isBlank() ? s : null;
        return new WorkTarget(k, name);
    }
}
