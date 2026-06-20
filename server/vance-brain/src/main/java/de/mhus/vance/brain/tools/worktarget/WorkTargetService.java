package de.mhus.vance.brain.tools.worktarget;

import de.mhus.vance.brain.tools.client.ClientToolRegistry;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.worktarget.WorkTarget;
import de.mhus.vance.shared.worktarget.WorkTargetKind;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Read/write surface for per-process {@link WorkTarget} state.
 *
 * <p>Storage: a Map under {@code engineParams[WorkTarget.KEY]}. The
 * service round-trips through {@link WorkTarget#toMap()} /
 * {@link WorkTarget#fromMap(Map)} so the on-disk form stays
 * schema-free and ports cleanly through recipe-param-copy on spawn.
 *
 * <p>{@link #current(ThinkProcessDocument)} resolves a default when
 * {@code engineParams} carries no explicit target: {@code CLIENT}
 * if a Foot client is bound to the session, otherwise {@code WORK}
 * with {@code dirName=null} (process-temp RootDir via
 * {@code WorkspaceDirResolver}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkTargetService {

    private final ThinkProcessService thinkProcessService;
    private final ClientToolRegistry clientToolRegistry;

    /**
     * Returns the current target for the process. Reads from
     * {@code engineParams}, falls back to {@link #defaultFor} when
     * nothing is set. Malformed maps log a warning and are treated
     * as "nothing set".
     */
    public WorkTarget current(ThinkProcessDocument process) {
        if (process == null) {
            throw new IllegalArgumentException("process is required");
        }
        Object raw = readWorkTargetEntry(process);
        if (raw instanceof Map<?, ?> map) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                return WorkTarget.fromMap(typed);
            } catch (IllegalArgumentException ex) {
                log.warn("WorkTargetService: malformed engineParams[workTarget] on id='{}' — falling back to default ({})",
                        process.getId(), ex.toString());
            }
        }
        return defaultFor(process);
    }

    /**
     * Convenience overload — looks the process up by id. Throws if
     * the process can't be found (caller should treat that as a
     * programming error).
     */
    public WorkTarget current(String processId) {
        ThinkProcessDocument process = thinkProcessService.findById(processId)
                .orElseThrow(() -> new IllegalStateException(
                        "WorkTargetService.current: unknown process '" + processId + "'"));
        return current(process);
    }

    /**
     * Persists {@code target} on the process atomically. Reads the
     * full {@code engineParams} map, sets {@code workTarget}, writes
     * back via {@link ThinkProcessService#replaceEngineParams}. The
     * existing rest of {@code engineParams} is preserved.
     */
    public void set(String processId, WorkTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        ThinkProcessDocument process = thinkProcessService.findById(processId)
                .orElseThrow(() -> new IllegalStateException(
                        "WorkTargetService.set: unknown process '" + processId + "'"));
        Map<String, Object> existing = process.getEngineParams();
        Map<String, Object> merged = existing == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        merged.put(WorkTarget.KEY, target.toMap());
        boolean updated = thinkProcessService.replaceEngineParams(processId, merged);
        if (!updated) {
            log.warn("WorkTargetService.set: replaceEngineParams returned false for '{}'", processId);
        }
    }

    /**
     * Default-resolution when the process has no explicit
     * {@code workTarget}. Prefers {@link WorkTargetKind#CLIENT} when
     * a Foot client is bound to the session, otherwise
     * {@link WorkTargetKind#WORK} with {@code dirName=null} (lazy
     * temp RootDir).
     */
    public WorkTarget defaultFor(ThinkProcessDocument process) {
        if (process != null && process.getSessionId() != null
                && clientToolRegistry.entry(process.getSessionId()).isPresent()) {
            return WorkTarget.client();
        }
        return WorkTarget.work(null);
    }

    /**
     * Whether a Foot client is bound to the process's session right
     * now. Tools dispatching to the CLIENT backend should sanity-check
     * this and emit a clear error when the client disconnected.
     */
    public boolean clientConnected(@Nullable String sessionId) {
        return sessionId != null && clientToolRegistry.entry(sessionId).isPresent();
    }

    private static @Nullable Object readWorkTargetEntry(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        return params == null ? null : params.get(WorkTarget.KEY);
    }
}
