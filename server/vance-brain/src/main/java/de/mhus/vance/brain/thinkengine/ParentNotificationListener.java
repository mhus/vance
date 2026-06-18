package de.mhus.vance.brain.thinkengine;

import de.mhus.vance.api.chat.ChatRole;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessStatusChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Maps life-cycle status transitions of a child process to a
 * {@code PROCESS_EVENT} on its parent's pending queue. The actual
 * appending + lane-wakeup is delegated to {@link ProcessEventEmitter}
 * so this class stays focused on the filter rules.
 *
 * <p>Filter:
 * <ul>
 *   <li>Skip top-level processes (no {@code parentProcessId}).</li>
 *   <li>Skip status repaints — same prior and new status.</li>
 *   <li>Skip intermediate transitions ({@code READY/RUNNING/PAUSED/SUSPENDED})
 *       — they're internal lane state, not something the parent needs
 *       to be woken for.</li>
 *   <li>Skip parent-initiated stops — when the parent itself called
 *       {@code process_stop} via {@link de.mhus.vance.brain.tools.process.ProcessStopTool},
 *       the resulting STOPPED event would loop back to it as redundant
 *       inbox material. {@link StopInitiatorRegistry} carries the
 *       initiator id so we can detect and suppress this case. Without
 *       the suppression, Arthur — and any other orchestrator — gets a
 *       phantom turn that the LLM sometimes interprets as "do
 *       something" (the classic spontaneous-restart symptom).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ParentNotificationListener {

    private final ProcessEventEmitter eventEmitter;
    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy because {@code ThinkEngineService} pulls in tools/recipes
     * which transitively reach this listener — direct dependency
     * would close the bean-graph cycle.
     */
    private final ObjectProvider<ThinkEngineService> thinkEngineServiceProvider;
    private final StopInitiatorRegistry stopInitiatorRegistry;
    private final ChatMessageService chatMessageService;
    /**
     * Translates technical engine output (Hactar return values,
     * Slart bookkeeping, GraalJS arity errors, …) into a natural
     * answer in the language of the user goal — applied only when
     * the emitting engine returns
     * {@link ThinkEngine#producesUserFacingOutput()} {@code = false}
     * and the event is DONE or FAILED. Lazy via {@link ObjectProvider}
     * so the bean graph stays acyclic (LightLlmService transitively
     * depends on RecipeResolver / model layers that reach this
     * listener indirectly).
     */
    private final ObjectProvider<de.mhus.vance.brain.ai.light.LightLlmService>
            lightLlmServiceProvider;

    @EventListener
    public void onStatusChanged(ThinkProcessStatusChangedEvent event) {
        String parentId = event.parentProcessId();
        if (parentId == null) {
            return;
        }
        if (event.priorStatus() == event.newStatus()) {
            return;
        }
        ProcessEventType eventType = mapStatus(event.processId(), event.newStatus());
        if (eventType == null) {
            return;
        }
        // Eddie holds a Working WS to every worker she delegates to —
        // see {@code specification/eddie-engine.md} §6/§7. When the
        // parent is registered as the WS-holder for this child (via
        // {@code workerLinks}), the lifecycle event must ride that WS,
        // not the engine-bind / Mongo-inbox detour the router does.
        // The Working-WS frame handler ({@code EddieChatFrameHandler})
        // is responsible for waking the parent's lane on those frames.
        // Without this guard, podless parent projects (e.g.
        // {@code _user_<login>}) try a cross-pod push that has nowhere
        // to land and the parent never gets woken.
        if (thinkProcessService.findWorkerLink(parentId, event.processId()).isPresent()) {
            log.debug("Parent {} watches child {} via Working WS — "
                            + "engine-bind notification suppressed (event={})",
                    parentId, event.processId(), eventType);
            return;
        }
        // Parent-initiated stops loop right back to the caller — suppress.
        if (eventType == ProcessEventType.STOPPED) {
            String initiator = stopInitiatorRegistry.consume(event.processId()).orElse(null);
            if (initiator != null && initiator.equals(parentId)) {
                log.debug("Parent {} stopped child {} itself — suppressing STOPPED notification",
                        parentId, event.processId());
                return;
            }
        }
        ParentReport report = buildReport(event.processId(), eventType, event.newStatus());
        // Engine-output translation: for engines whose terminal
        // events carry technical plumbing (Hactar return value,
        // Slart bookkeeping, GraalJS stack traces) rather than a
        // user-facing reply, route DONE/FAILED through the
        // engine-output-translator LightLlm recipe. The translated
        // text replaces the humanSummary and we skip
        // enrichWithLastReply so the raw engine body doesn't get
        // re-attached as a "Last reply" block — the translator
        // already has it as input.
        boolean translated = false;
        String summaryForParent = report.humanSummary();
        if (shouldTranslateEngineOutput(event.processId(), eventType)) {
            String translation = translateEngineOutput(
                    event.processId(), eventType, report.humanSummary());
            if (translation != null && !translation.isBlank()) {
                summaryForParent = translation;
                translated = true;
            }
        }
        String enrichedSummary = translated
                ? summaryForParent
                : enrichWithLastReply(event.processId(), summaryForParent);
        // Attribution: which user-input turn was the worker responding
        // to when it produced this event? Lets the parent engine (e.g.
        // Arthur) distinguish a fresh reply from a stale one — without
        // this, a Ford reply that arrived during an Arthur turn that
        // spawned Marvin gets relayed as if it were Marvin's output.
        // See planning/arthur-process-event-attribution.md.
        Instant inResponseToAt = findLastUserInputAt(event.processId());
        boolean queued = eventEmitter.notifyParent(
                parentId,
                event.processId(),
                eventType,
                enrichedSummary,
                report.payload(),
                inResponseToAt);
        if (queued) {
            log.info("Parent notify queued parent='{}' child='{}' event={}",
                    parentId, event.processId(), eventType);
        }
    }

    /**
     * Asks the child's engine for its parent-report. Falls back to
     * a generic "child status" line if the engine throws or the
     * process row is gone — never let a hook failure swallow the
     * parent-notification.
     */
    private ParentReport buildReport(
            String childProcessId,
            ProcessEventType eventType,
            ThinkProcessStatus newStatus) {
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findById(childProcessId);
        if (processOpt.isEmpty()) {
            return ParentReport.of(genericSummary(childProcessId, newStatus));
        }
        ThinkProcessDocument process = processOpt.get();
        try {
            ThinkEngine engine = thinkEngineServiceProvider.getObject()
                    .resolveForProcess(process);
            return engine.summarizeForParent(process, eventType);
        } catch (RuntimeException e) {
            log.warn("summarizeForParent failed for child='{}' engine='{}': {}",
                    childProcessId, process.getThinkEngine(), e.toString());
            return ParentReport.of(genericSummary(childProcessId, newStatus));
        }
    }

    /**
     * Appends the child's last ASSISTANT chat message to the
     * engine-supplied human summary so the parent's LLM sees the
     * actual content the child produced — the recipe text, the
     * clarification question, the analysis result — rather than
     * just the lifecycle marker. Defensive: if there is no last
     * ASSISTANT message (rare — a worker that closed without ever
     * replying), the engine summary is returned untouched.
     */
    private String enrichWithLastReply(String childProcessId, String engineSummary) {
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findById(childProcessId);
        if (processOpt.isEmpty()) {
            return engineSummary;
        }
        ThinkProcessDocument process = processOpt.get();
        try {
            List<ChatMessageDocument> history = chatMessageService.activeHistory(
                    process.getTenantId(), process.getSessionId(), process.getId());
            ChatMessageDocument lastAssistant = null;
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessageDocument m = history.get(i);
                if (m.getRole() == ChatRole.ASSISTANT
                        && m.getContent() != null
                        && !m.getContent().isBlank()) {
                    lastAssistant = m;
                    break;
                }
            }
            if (lastAssistant == null) {
                return engineSummary;
            }
            return engineSummary
                    + "\n\nLast assistant reply from this child (verbatim):\n"
                    + "--- BEGIN CHILD REPLY ---\n"
                    + lastAssistant.getContent()
                    + "\n--- END CHILD REPLY ---";
        } catch (RuntimeException e) {
            log.warn("enrichWithLastReply failed for child='{}': {}",
                    childProcessId, e.toString());
            return engineSummary;
        }
    }

    /**
     * Decides whether the technical {@code rawSummary} of a terminal
     * event needs to be rendered into natural language before being
     * shipped to the parent. True iff the emitting engine signals
     * {@link ThinkEngine#producesUserFacingOutput()} {@code = false}
     * AND the event is {@code DONE} or {@code FAILED}. BLOCKED is
     * out of scope here — parking events don't need a human reply
     * (Arthur's Auto-WAIT short-circuits them entirely).
     */
    private boolean shouldTranslateEngineOutput(
            String childProcessId, ProcessEventType eventType) {
        if (eventType != ProcessEventType.DONE
                && eventType != ProcessEventType.FAILED) {
            return false;
        }
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findById(childProcessId);
        if (processOpt.isEmpty()) {
            return false;
        }
        try {
            ThinkEngine engine = thinkEngineServiceProvider.getObject()
                    .resolveForProcess(processOpt.get());
            return !engine.producesUserFacingOutput();
        } catch (RuntimeException e) {
            log.warn("producesUserFacingOutput probe failed for child='{}': {}",
                    childProcessId, e.toString());
            return false;
        }
    }

    /**
     * Calls the {@code engine-output-translator} LightLlm recipe with
     * the user's goal, the engine identity and the raw technical
     * summary; returns the LLM's natural-language reply (or
     * {@code null} on any failure — the caller falls back to the raw
     * summary so a translator outage never silences a parent
     * notification).
     */
    private @Nullable String translateEngineOutput(
            String childProcessId,
            ProcessEventType eventType,
            String rawSummary) {
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findById(childProcessId);
        if (processOpt.isEmpty()) {
            return null;
        }
        ThinkProcessDocument child = processOpt.get();
        de.mhus.vance.brain.ai.light.LightLlmService service =
                lightLlmServiceProvider.getIfAvailable();
        if (service == null) {
            return null;
        }
        java.util.Map<String, Object> vars = new java.util.LinkedHashMap<>();
        vars.put("userGoal", child.getGoal() == null ? "" : child.getGoal());
        vars.put("eventType", eventType.name());
        vars.put("engineName", child.getThinkEngine() == null
                ? "unknown" : child.getThinkEngine());
        vars.put("rawSummary", rawSummary == null ? "" : rawSummary);
        try {
            String reply = service.call(
                    de.mhus.vance.brain.ai.light.LightLlmRequest.builder()
                            .recipeName("engine-output-translator")
                            .userPrompt("Translate the engine output above.")
                            .pebbleVars(vars)
                            .tenantId(child.getTenantId())
                            .projectId(child.getProjectId())
                            .processId(child.getId())
                            .build());
            if (reply == null || reply.isBlank()) {
                return null;
            }
            return reply.trim();
        } catch (RuntimeException e) {
            log.warn(
                    "engine-output-translator failed for child='{}' engine='{}' event={}: {}",
                    child.getId(), child.getThinkEngine(), eventType, e.toString());
            return null;
        }
    }

    /**
     * Reads the child's chat history and returns the timestamp of its
     * most recent USER message — that's the user-input turn the child
     * was processing when this event was produced. The parent engine
     * uses this to distinguish a fresh reply (matches the current
     * outgoing user-input timestamp) from a stale one (matches an
     * older turn that the parent has since moved past).
     *
     * <p>Returns {@code null} when the process row is gone, the
     * history is empty, no USER message exists yet, or the lookup
     * throws — never let attribution failure swallow the
     * notification.
     */
    private @Nullable Instant findLastUserInputAt(String childProcessId) {
        Optional<ThinkProcessDocument> processOpt =
                thinkProcessService.findById(childProcessId);
        if (processOpt.isEmpty()) {
            return null;
        }
        ThinkProcessDocument process = processOpt.get();
        try {
            List<ChatMessageDocument> history = chatMessageService.activeHistory(
                    process.getTenantId(), process.getSessionId(), process.getId());
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessageDocument m = history.get(i);
                if (m.getRole() == ChatRole.USER && m.getCreatedAt() != null) {
                    return m.getCreatedAt();
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("findLastUserInputAt failed for child='{}': {}",
                    childProcessId, e.toString());
            return null;
        }
    }

    private @Nullable ProcessEventType mapStatus(String processId, ThinkProcessStatus status) {
        return switch (status) {
            // BLOCKED is pure lane state in the REPLY-channel model
            // (planning/process-engine-reply-channel.md §2.2). Engines
            // that have a user-facing reply emit it explicitly via
            // {@code ProgressEmitter.emitReply}; pure parking events
            // (Slart on Hactar, Marvin awaiting children, …) leave
            // the parent silent. No more BLOCKED notification.
            case BLOCKED -> null;
            case CLOSED -> mapClosedToEventType(processId);
            case INIT, RUNNING, IDLE, PAUSED, SUSPENDED -> null;
        };
    }

    private ProcessEventType mapClosedToEventType(String processId) {
        CloseReason reason = thinkProcessService.findById(processId)
                .map(ThinkProcessDocument::getCloseReason)
                .orElse(null);
        if (reason == null) {
            return ProcessEventType.STOPPED;
        }
        return switch (reason) {
            case DONE -> ProcessEventType.DONE;
            case STOPPED, AUTO_CLOSE -> ProcessEventType.STOPPED;
            case STALE -> ProcessEventType.FAILED;
            // Session-driven terminal states (engine was shut down as
            // part of an archive / hard-delete / abandoned-detection
            // cascade). The parent (if any) is being torn down too —
            // treat as a quiet STOPPED for audit purposes.
            case ARCHIVED, USER_DELETE, ABANDONED -> ProcessEventType.STOPPED;
        };
    }

    private static String genericSummary(String childProcessId, ThinkProcessStatus status) {
        return "Child process " + childProcessId + " status=" + status.name().toLowerCase();
    }
}
