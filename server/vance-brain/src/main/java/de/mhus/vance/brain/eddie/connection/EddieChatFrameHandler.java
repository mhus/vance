package de.mhus.vance.brain.eddie.connection;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.eddie.triage.OutputTriageService;
import de.mhus.vance.brain.eddie.triage.TriageInput;
import de.mhus.vance.brain.eddie.triage.TriageResult;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Triage-driven chat-frame handler — wires the {@link OutputTriageService}
 * into Eddie's Working-WS receive loop and writes the resulting summary
 * + criticality back to the {@link WorkerLinkSnapshot}.
 *
 * <h2>What this handler does</h2>
 *
 * <ul>
 *   <li>Listens for {@link MessageType#CHAT_MESSAGE_APPENDED} frames —
 *       these are the worker's <i>finalized</i> chat replies. Stream-
 *       chunks are skipped: triaging mid-stream would burn budget and
 *       give a false snapshot of partial text.</li>
 *   <li>Skips {@link ChatRole#USER} frames — Eddie is the speaker
 *       there, no need to triage her own input.</li>
 *   <li>Runs the heuristic triage, applies the hard-override (clamp
 *       {@code CRITICAL+REFORMULATE} → {@code INBOX}), and persists
 *       {@code triageSummary} + {@code lastCriticality} +
 *       {@code lastSeen} on the link via
 *       {@link ThinkProcessService#upsertWorkerLink}.</li>
 * </ul>
 *
 * <h2>What this handler does NOT do (yet)</h2>
 *
 * <ul>
 *   <li>It does not push the worker's text into Eddie's pending queue
 *       or annotate it with a {@code relayMode}/{@code spokenAnnouncement}.
 *       That step (deterministic action-mapping in the EddieEngine)
 *       is the next iteration. For now Eddie's lane sees the triage
 *       summary in her {@code <delegated_workers>} prompt block and
 *       decides RELAY / RELAY_INBOX herself, same as today.</li>
 *   <li>It does not re-triage on stream-chunks — only on the
 *       authoritative {@code CHAT_MESSAGE_APPENDED} frame.</li>
 * </ul>
 *
 * <h2>Eddie process scope</h2>
 *
 * The handler needs to know which Eddie process owns the connection
 * (so it can write back to the right {@code workerLinks} array). The
 * pool keys connections by {@code (eddieProcessId, workerProcessId)};
 * {@link EddieWorkerConnectionPool#findEddieIdForWorker} provides the
 * reverse lookup we need here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EddieChatFrameHandler implements EddieFrameRouter.ChatFrameHandler {

    private final OutputTriageService triageService;
    private final ThinkProcessService thinkProcessService;
    private final ObjectMapper objectMapper;
    private final EddieWorkerConnectionPool pool;

    @Override
    public void onChatFrame(WebSocketEnvelope envelope, WorkerLinkSnapshot link) {
        // Stream chunks are partial and noisy — only triage on the
        // committed ChatMessageAppendedData payload. Other types fall
        // through silently.
        if (!MessageType.CHAT_MESSAGE_APPENDED.equals(envelope.getType())) {
            return;
        }
        ChatMessageAppendedData data;
        try {
            data = objectMapper.convertValue(envelope.getData(), ChatMessageAppendedData.class);
        } catch (RuntimeException e) {
            log.debug("EddieChatFrameHandler: malformed chat-message-appended for worker={}: {}",
                    link.getWorkerProcessId(), e.toString());
            return;
        }
        if (data == null || data.getContent() == null || data.getContent().isBlank()) {
            return;
        }
        if (data.getRole() != ChatRole.ASSISTANT) {
            // User / system messages aren't worker output we'd surface to the
            // user — Eddie sent them in the first place.
            return;
        }

        TriageInput input = new TriageInput(
                data.getContent(),
                /*outputHint=*/ null,
                link.getWorkerProcessName(),
                /*voiceMode=*/ true);
        TriageResult result;
        try {
            TriageResult raw = triageService.classify(input);
            result = triageService.applyHardOverrides(raw, input);
        } catch (RuntimeException e) {
            log.warn("EddieChatFrameHandler: triage failed for worker={}: {}",
                    link.getWorkerProcessId(), e.toString());
            return;
        }

        // Update the in-memory link directly so other consumers (the
        // prompt-block render running on Eddie's lane) see the update
        // immediately, plus persist for resume / cross-pod survival.
        link.setTriageSummary(result.memorySummary());
        link.setLastCriticality(result.criticality());
        link.setLastSeen(Instant.now());

        String eddieProcessId = pool.findEddieIdForWorker(link.getWorkerProcessId())
                .orElse(null);
        if (eddieProcessId == null) {
            log.debug("EddieChatFrameHandler: no Eddie owner for worker={}, snapshot updated in-memory only",
                    link.getWorkerProcessId());
            return;
        }
        try {
            thinkProcessService.upsertWorkerLink(eddieProcessId, link);
        } catch (RuntimeException e) {
            log.warn("EddieChatFrameHandler: upsertWorkerLink failed eddie={} worker={}: {}",
                    eddieProcessId, link.getWorkerProcessId(), e.toString());
        }
    }
}
