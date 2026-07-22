package de.mhus.vance.brain.damogran;

import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.brain.enginemessage.EngineMessageRouter;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Dispatches a {@link ProcessEventType#COMPOSE_FINISHED} event to the process
 * that started an async compose run, so it can end its turn and resume on the
 * event when the run completes. Shared by {@code compose_run} and
 * {@code compose_block_run}.
 */
@Component
@Slf4j
class ComposeFinishedNotifier {

    private final ObjectProvider<EngineMessageRouter> engineMessageRouterProvider;

    ComposeFinishedNotifier(ObjectProvider<EngineMessageRouter> engineMessageRouterProvider) {
        this.engineMessageRouterProvider = engineMessageRouterProvider;
    }

    void notifyFinished(ComposeRun run, String ownerProcessId) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", run.runId());
            payload.put("status", run.status().name());
            payload.put("workspace", run.workspaceName());
            payload.put("projectId", run.projectId());
            if (run.error() != null) {
                payload.put("error", run.error());
            }
            if (run.result() != null) {
                payload.put("result", DamogranResponse.toMap(run.result()));
            }
            String summary = "Compose " + run.runId() + " "
                    + run.status().name().toLowerCase(Locale.ROOT);
            PendingMessageDocument doc = PendingMessageDocument.builder()
                    .type(PendingMessageType.PROCESS_EVENT)
                    .at(Instant.now())
                    .sourceProcessId(ownerProcessId)
                    .eventType(ProcessEventType.COMPOSE_FINISHED)
                    .content(summary)
                    .payload(payload)
                    .eventId(UUID.randomUUID().toString())
                    .build();
            boolean ok = engineMessageRouterProvider.getObject().dispatch(ownerProcessId, ownerProcessId, doc);
            if (!ok) {
                log.warn("COMPOSE_FINISHED dispatch dropped owner='{}' run='{}'", ownerProcessId, run.runId());
            }
        } catch (RuntimeException e) {
            log.warn("COMPOSE_FINISHED dispatch failed owner='{}' run='{}': {}",
                    ownerProcessId, run.runId(), e.toString());
        }
    }
}
