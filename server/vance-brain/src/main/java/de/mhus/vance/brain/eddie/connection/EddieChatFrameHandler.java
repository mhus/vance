package de.mhus.vance.brain.eddie.connection;

import de.mhus.vance.api.chat.ChatMessageAppendedData;
import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.api.ws.WebSocketEnvelope;
import de.mhus.vance.brain.eddie.triage.OutputTriageService;
import de.mhus.vance.brain.eddie.triage.TriageDecision;
import de.mhus.vance.brain.eddie.triage.TriageInput;
import de.mhus.vance.brain.eddie.triage.TriageResult;
import de.mhus.vance.brain.events.ClientEventPublisher;
import de.mhus.vance.brain.thinkengine.ProcessEventEmitter;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.thinkprocess.PendingMessageDocument;
import de.mhus.vance.shared.thinkprocess.PendingMessageType;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
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
    private final ClientEventPublisher clientEventPublisher;
    private final ProcessEventEmitter processEventEmitter;

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
        // Resolve the Eddie process so the LLM-stage of the triage can
        // run with the right tenant/project for settings cascade. The
        // pool reverse-lookup is the canonical mapping.
        String eddieIdForLlm = pool.findEddieIdForWorker(link.getWorkerProcessId())
                .orElse(null);
        ThinkProcessDocument eddieContext = eddieIdForLlm == null
                ? null
                : thinkProcessService.findById(eddieIdForLlm).orElse(null);

        TriageResult result;
        try {
            // classifyWithContext degrades to heuristic when no LLM
            // stage bean is registered; the hard-override clamp is
            // applied inside.
            result = eddieContext != null
                    ? triageService.classifyWithContext(input, eddieContext)
                    : triageService.applyHardOverrides(triageService.classify(input), input);
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

        // Deterministic action mapping — the part the LLM doesn't have
        // to decide. v1: only VERBATIM is auto-forwarded; INBOX +
        // REFORMULATE still go through the LLM on the next Eddie turn
        // (the worker-link snapshot already carries the triage summary,
        // so the <delegated_workers> prompt block surfaces it).
        if (result.decision() == TriageDecision.VERBATIM) {
            forwardVerbatim(eddieProcessId, data);
        } else {
            // Non-VERBATIM = Eddie has to decide RELAY / RELAY_INBOX
            // herself based on the snapshot's triage summary. The
            // engine-bind PROCESS_EVENT path is suppressed for
            // workers Eddie watches (see {@link ParentNotificationListener}),
            // so we wake her lane here directly: synthesise a
            // PROCESS_EVENT in her pending queue + schedule a turn.
            // Without this hand-off Eddie would never speak again
            // after a long worker reply.
            wakeEddieForWorkerReply(eddieProcessId, link, data, result);
        }
    }

    private void wakeEddieForWorkerReply(
            String eddieProcessId,
            WorkerLinkSnapshot link,
            ChatMessageAppendedData data,
            TriageResult result) {
        String summary = result.memorySummary() != null && !result.memorySummary().isBlank()
                ? result.memorySummary()
                : data.getContent();
        PendingMessageDocument event = PendingMessageDocument.builder()
                .type(PendingMessageType.PROCESS_EVENT)
                .at(Instant.now())
                .sourceProcessId(link.getWorkerProcessId())
                .eventType(ProcessEventType.SUMMARY)
                .content(summary)
                .build();
        try {
            boolean appended = thinkProcessService.appendPending(
                    eddieProcessId, event, link.getWorkerProcessId());
            if (!appended) {
                log.debug("wakeEddieForWorkerReply: Eddie process gone id='{}'",
                        eddieProcessId);
                return;
            }
            processEventEmitter.scheduleTurn(eddieProcessId);
        } catch (RuntimeException e) {
            log.warn("wakeEddieForWorkerReply: failed eddie={} worker={}: {}",
                    eddieProcessId, link.getWorkerProcessId(), e.toString());
        }
    }

    /**
     * Sends the worker's chat-message-appended frame to Eddie's user
     * session, rewriting {@code thinkProcessId} / {@code processName}
     * so the user-client renders it as a message originating from
     * Eddie (the actual carrier — keeps the chat thread coherent).
     */
    private void forwardVerbatim(String eddieProcessId, ChatMessageAppendedData workerData) {
        ThinkProcessDocument eddie = thinkProcessService.findById(eddieProcessId).orElse(null);
        if (eddie == null) return;
        String sessionId = eddie.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        ChatMessageAppendedData out = ChatMessageAppendedData.builder()
                .chatMessageId(workerData.getChatMessageId())
                .thinkProcessId(eddie.getId())
                .processName(eddie.getName())
                .role(workerData.getRole())
                .content(workerData.getContent())
                .createdAt(workerData.getCreatedAt())
                .meta(workerData.getMeta())
                .build();

        try {
            clientEventPublisher.publish(sessionId, MessageType.CHAT_MESSAGE_APPENDED, out);
        } catch (RuntimeException e) {
            log.debug("EddieChatFrameHandler: verbatim forward to session='{}' failed: {}",
                    sessionId, e.toString());
        }
    }
}
