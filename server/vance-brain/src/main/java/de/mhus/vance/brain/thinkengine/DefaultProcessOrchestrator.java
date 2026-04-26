package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Per-call {@link ProcessOrchestrator} bound to one process. Built
 * fresh by {@link DefaultThinkEngineContext} for every lifecycle
 * invocation — never cached.
 */
final class DefaultProcessOrchestrator implements ProcessOrchestrator {

    private final ThinkProcessDocument process;
    private final ThinkProcessService thinkProcessService;
    private final ProcessEventEmitter eventEmitter;

    DefaultProcessOrchestrator(
            ThinkProcessDocument process,
            ThinkProcessService thinkProcessService,
            ProcessEventEmitter eventEmitter) {
        this.process = process;
        this.thinkProcessService = thinkProcessService;
        this.eventEmitter = eventEmitter;
    }

    @Override
    public boolean notifyParent(
            ProcessEventType type,
            String humanSummary,
            @Nullable Map<String, Object> payload) {
        String parentId = process.getParentProcessId();
        if (parentId == null) {
            return false;
        }
        return eventEmitter.notifyParent(
                parentId, process.getId(), type, humanSummary, payload);
    }

    @Override
    public List<ThinkProcessDocument> siblings() {
        String myId = process.getId();
        return thinkProcessService
                .findBySession(process.getTenantId(), process.getSessionId())
                .stream()
                .filter(p -> !Objects.equals(p.getId(), myId))
                .toList();
    }
}
