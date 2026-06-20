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
 * <p>{@code dirName} is only meaningful when {@link #kind()} is
 * {@link WorkTargetKind#WORK}. For {@link WorkTargetKind#CLIENT}
 * the field is ignored — Foot operates against its own configured
 * {@code --workdir}, not against a Brain-side RootDir.
 */
public record WorkTarget(WorkTargetKind kind, @Nullable String dirName) {

    /** {@code engineParams} key under which the persisted Map lives. */
    public static final String KEY = "workTarget";

    /** Sub-key for {@link #kind()} in the persisted Map. */
    public static final String FIELD_KIND = "kind";

    /** Sub-key for {@link #dirName()} in the persisted Map. */
    public static final String FIELD_DIR_NAME = "dirName";

    public WorkTarget {
        if (kind == null) {
            throw new IllegalArgumentException("WorkTarget.kind is required");
        }
    }

    /** Shortcut for the user-local Foot-CLI surface. */
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

    /** Round-trips into the form persisted on {@code engineParams[KEY]}. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(FIELD_KIND, kind.name());
        if (dirName != null && !dirName.isBlank()) {
            out.put(FIELD_DIR_NAME, dirName);
        }
        return out;
    }

    /**
     * Inverse of {@link #toMap()}. Tolerates legacy lowercase /
     * mixed-case {@code kind} strings; throws
     * {@link IllegalArgumentException} on missing or unparseable
     * input so a malformed recipe / engineParams surfaces cleanly.
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
        Object rawDir = raw.get(FIELD_DIR_NAME);
        String dir = rawDir instanceof String s && !s.isBlank() ? s : null;
        return new WorkTarget(k, dir);
    }
}
