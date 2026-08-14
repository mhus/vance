package de.mhus.vance.brain.vogon;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.api.magrathea.RunCapability;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ProcessEventType;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.magrathea.MagratheaOwnerNotifier;
import de.mhus.vance.brain.magrathea.MagratheaWorkflowService;
import de.mhus.vance.brain.thinkengine.ParentReport;
import de.mhus.vance.brain.thinkengine.SteerMessage;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineContext;
import de.mhus.vance.shared.magrathea.MagratheaRunBinding;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Vogon — a plan that belongs to somebody.
 *
 * <p>Mechanically this is a Magrathea run: same grammar, same journal,
 * same task types, same deadlines and watchdog. What makes it a separate
 * engine is not the machinery but the <b>job</b>. A Magrathea run belongs
 * to a project and to nobody in particular — if it wants something it
 * leaves an inbox item and waits for whoever comes by. A Vogon run belongs
 * to a person: it stands inside a conversation, it can ask there, and when
 * it is finished it answers back into it.
 *
 * <p>That difference cannot be expressed as a recipe. Recipes configure
 * what an engine does; they cannot decide <em>what gets bound at spawn</em>
 * — the session and the owning process — and every capability above comes
 * from that binding.
 *
 * <p>So this class is thin on purpose. It binds, and it translates:
 *
 * <ul>
 *   <li>{@code start} — begins a run bound to this process and its session</li>
 *   <li>{@code runTurn} — hands what the person said to a waiting gate, and
 *       reacts to what the run reports back</li>
 *   <li>{@code summarizeForParent} — reads the run's result out of the
 *       journal for whoever spawned this process</li>
 *   <li>{@code suspend}/{@code resume}/{@code stop} — the session's control
 *       verbs, applied to the run</li>
 * </ul>
 *
 * <p>Everything else — advancing states, retries, counters, judgements,
 * bounds — is the runner's, and deliberately not duplicated here.
 *
 * <p>See {@code specification/public/vogon-engine.md} and
 * {@code planning/vogon-magrathea-merge.md}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VogonEngine implements ThinkEngine {

    public static final String NAME = "vogon";
    public static final String VERSION = "1.0.0";

    /** Plan to run, by name, resolved through the workflow cascade. */
    public static final String PARAM_WORKFLOW = "workflow";

    /** Plan to run, by document path inside the process's project. */
    public static final String PARAM_WORKFLOW_PATH = "workflowPath";

    /** Where the run id is kept, so every later turn knows what it owns. */
    public static final String PARAM_RUN_ID = "workflowRunId";

    private final MagratheaWorkflowService workflowService;
    private final MagratheaStateProjector projector;
    private final MagratheaGateChatAnswerService gateChatAnswerService;
    private final ThinkProcessService thinkProcessService;
    private final SessionService sessionService;
    /** Optional: absent on a pod where Magrathea is switched off. */
    private final ObjectProvider<de.mhus.vance.brain.progress.ProgressEmitter> progressEmitter;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String title() {
        return "Vogon";
    }

    @Override
    public String description() {
        return "Runs a written plan on behalf of a person: the same state machine "
                + "workflows use, bound to this session so it can ask in the "
                + "conversation and answer back into it.";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /**
     * Empty, and staying empty. Vogon does not decide anything — the plan
     * does. Every judgement in a run is made by a worker the plan spawned,
     * with that worker's own tools.
     */
    @Override
    public Set<String> allowedTools() {
        return Set.of();
    }

    @Override
    public boolean asyncSteer() {
        // Event-driven: gate answers and run reports arrive on their own
        // schedule, and an orchestrator must not block waiting for them.
        return true;
    }

    /**
     * The run below is the thing with steps, not this process. Showing both
     * would list one plan twice — once as phases, once as states — and
     * leave the reader to guess which is real.
     */
    @Override
    public boolean planShaped() {
        return false;
    }

    // ──────────────────── lifecycle ────────────────────

    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        MagratheaRunBinding binding = bindingFor(process);
        String runId;
        try {
            runId = startRun(process, binding);
        } catch (RuntimeException ex) {
            log.warn("Vogon id='{}' could not start its plan: {}", process.getId(), ex.toString());
            // A plan that cannot start is not a process that should linger:
            // the caller gets the reason through the close, immediately,
            // instead of a process that idles forever with nothing behind it.
            thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            throw ex;
        }
        rememberRunId(process, runId);
        log.info("Vogon id='{}' started run '{}' (session='{}', capabilities={})",
                process.getId(), runId, process.getSessionId(), binding.capabilities());
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    private String startRun(ThinkProcessDocument process, MagratheaRunBinding binding) {
        Map<String, Object> params = callerParams(process);
        String path = stringParam(process, PARAM_WORKFLOW_PATH);
        if (path != null) {
            return workflowService.startFromDocument(
                    process.getTenantId(), process.getProjectId(), path, params,
                    startedBy(process), binding);
        }
        String workflow = stringParam(process, PARAM_WORKFLOW);
        if (workflow == null) {
            throw new IllegalArgumentException(
                    "Vogon needs a plan: set engineParams." + PARAM_WORKFLOW
                            + " (name) or " + PARAM_WORKFLOW_PATH + " (document path)");
        }
        return workflowService.start(
                process.getTenantId(), process.getProjectId(), workflow, params,
                startedBy(process), /* parentRun */ null, /* parentState */ null, binding);
    }

    /**
     * What this run is bound to.
     *
     * <p>{@code OWNER_PROCESS} is always present — this process is the
     * owner, by construction. {@code USER_SESSION} depends on whose session
     * it runs in: a real person's, or a system one (scheduler, hook). That
     * is a property of the session document, not of whether anybody happens
     * to be connected right now.
     */
    private MagratheaRunBinding bindingFor(ThinkProcessDocument process) {
        Set<RunCapability> caps = new java.util.LinkedHashSet<>();
        caps.add(RunCapability.OWNER_PROCESS);
        if (hasHumanOwner(process.getTenantId(), process.getSessionId())) {
            caps.add(RunCapability.USER_SESSION);
        }
        return new MagratheaRunBinding(process.getSessionId(), process.getId(), caps);
    }

    private boolean hasHumanOwner(String tenantId, @Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return sessionService.findBySessionId(sessionId)
                .filter(s -> !s.isSystem())
                .map(SessionDocument::getUserId)
                .filter(u -> u != null && !u.isBlank())
                .isPresent();
    }

    @Override
    public void resume(ThinkProcessDocument process, ThinkEngineContext ctx) {
        withRun(process, runId -> {
            workflowService.resumeRun(process.getTenantId(), process.getProjectId(), runId);
            log.info("Vogon id='{}' resumed run '{}'", process.getId(), runId);
        });
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
    }

    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        withRun(process, runId -> {
            workflowService.pauseRun(process.getTenantId(), process.getProjectId(), runId);
            log.info("Vogon id='{}' paused run '{}'", process.getId(), runId);
        });
    }

    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        withRun(process, runId -> {
            workflowService.stopRun(process.getTenantId(), process.getProjectId(), runId,
                    "owner process stopped");
            log.info("Vogon id='{}' stopped run '{}'", process.getId(), runId);
        });
    }

    // ──────────────────── turns ────────────────────

    @Override
    public void steer(
            ThinkProcessDocument process, ThinkEngineContext ctx, SteerMessage message) {
        handle(process, message);
    }

    @Override
    public void runTurn(ThinkProcessDocument process, ThinkEngineContext ctx) {
        for (SteerMessage message : ctx.drainPending()) {
            handle(process, message);
        }
    }

    /**
     * Two kinds of message reach a Vogon process, and they come from
     * opposite directions: the person above it, and the run below it.
     */
    private void handle(ThinkProcessDocument process, SteerMessage message) {
        switch (message) {
            case SteerMessage.UserChatInput input -> onUserSaid(process, input);
            case SteerMessage.ProcessEvent event -> onRunReported(process, event);
            default -> log.trace("Vogon id='{}' ignoring {}",
                    process.getId(), message.getClass().getSimpleName());
        }
    }

    /**
     * The person said something while the run is going.
     *
     * <p>If the run is sitting at a gate, this may well be the answer to it
     * — a reply in the conversation instead of a click in the inbox. It is
     * written as the same inbox answer either way, so both routes end in
     * one place with one audit trail.
     *
     * <p>If it is not readable as an answer, nothing happens. The gate stays
     * open, the inbox item is still there, and the person can say it
     * differently or use the form. Not understanding a sentence is a normal
     * outcome, not a failure.
     */
    private void onUserSaid(ThinkProcessDocument process, SteerMessage.UserChatInput input) {
        String runId = runId(process);
        if (runId == null) return;
        String text = input.content();
        if (text == null || text.isBlank()) return;

        boolean answered = gateChatAnswerService.tryAnswer(
                process.getTenantId(), runId, text,
                input.fromUser() != null ? input.fromUser() : startedBy(process));
        if (answered) {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        } else {
            log.debug("Vogon id='{}' — nothing in run '{}' was waiting for that",
                    process.getId(), runId);
        }
    }

    /**
     * The run below reported that it is waiting, or that it is finished.
     *
     * <p>Every process event reaching a Vogon process comes from its run:
     * the workers a plan spawns are parented to nothing
     * ({@code AgentTaskExecutor} passes a null parent), so there is no
     * second source of these to tell apart.
     */
    private void onRunReported(ThinkProcessDocument process, SteerMessage.ProcessEvent event) {
        ProcessEventType type = event.type();
        if (type == null) return;
        log.info("Vogon id='{}' run reported {}", process.getId(), type);

        switch (type) {
            case BLOCKED -> {
                // Parked on a person. Going BLOCKED is what makes the parent
                // (and through it the UI) see that this is waiting rather
                // than working — the notification cascade rides the status.
                thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.BLOCKED);
                emitStatus(process, de.mhus.vance.api.progress.StatusTag.WAITING, event.humanSummary());
            }
            case DONE -> thinkProcessService.closeProcess(process.getId(), CloseReason.DONE);
            case FAILED -> thinkProcessService.closeProcess(process.getId(), CloseReason.STALE);
            case STOPPED -> thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
            default -> log.trace("Vogon id='{}' ignoring run event {}", process.getId(), type);
        }
    }

    // ──────────────────── result ────────────────────

    /**
     * What the spawner of this process gets to see.
     *
     * <p>Read from the journal rather than remembered: the run is the
     * authority on its own result, and a copy kept here could only ever be
     * a second answer that might disagree.
     */
    @Override
    public ParentReport summarizeForParent(
            ThinkProcessDocument process, ProcessEventType eventType) {
        String runId = runId(process);
        if (runId == null) {
            return ParentReport.of("The plan never started.");
        }
        Optional<MagratheaProcessDto> run = projector.project(
                process.getTenantId(), process.getProjectId(), runId);
        if (run.isEmpty()) {
            return ParentReport.of("Plan run " + runId + " left no journal.");
        }
        MagratheaProcessDto dto = run.get();
        Map<String, Object> payload = new LinkedHashMap<>();
        if (dto.getResult() != null) payload.putAll(dto.getResult());
        payload.put("workflowRunId", runId);
        payload.put("workflowName", dto.getWorkflowName());
        payload.put("status", String.valueOf(dto.getStatus()));

        return new ParentReport(humanSummary(dto), payload);
    }

    private static String humanSummary(MagratheaProcessDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan '").append(dto.getWorkflowName()).append("' ");
        sb.append(dto.getStatus() == MagratheaRunStatus.DONE ? "finished" : "ended");
        if (dto.getStatus() != null && dto.getStatus() != MagratheaRunStatus.DONE) {
            sb.append(" as ").append(dto.getStatus().name().toLowerCase(java.util.Locale.ROOT));
        }
        if (dto.getCurrentState() != null) {
            sb.append(" at '").append(dto.getCurrentState()).append("'");
        }
        sb.append('.');
        if (dto.getResult() != null && !dto.getResult().isEmpty()) {
            sb.append("\n\nResult:\n");
            dto.getResult().forEach((k, v) -> sb.append("- ").append(k)
                    .append(": ").append(v).append('\n'));
        }
        return sb.toString();
    }

    // ──────────────────── helpers ────────────────────

    private void withRun(ThinkProcessDocument process, java.util.function.Consumer<String> action) {
        String runId = runId(process);
        if (runId == null) return;
        try {
            action.accept(runId);
        } catch (RuntimeException ex) {
            log.warn("Vogon id='{}' run control failed for '{}': {}",
                    process.getId(), runId, ex.toString());
        }
    }

    private void emitStatus(
            ThinkProcessDocument process,
            de.mhus.vance.api.progress.StatusTag tag,
            @Nullable String text) {
        var emitter = progressEmitter.getIfAvailable();
        if (emitter == null) return;
        try {
            emitter.emitStatus(process, tag, text == null ? "" : text);
        } catch (RuntimeException ex) {
            log.trace("Vogon id='{}' progress emit failed: {}", process.getId(), ex.toString());
        }
    }

    private void rememberRunId(ThinkProcessDocument process, String runId) {
        Map<String, Object> params = process.getEngineParams() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(process.getEngineParams());
        params.put(PARAM_RUN_ID, runId);
        thinkProcessService.replaceEngineParams(process.getId(), params);
    }

    private @Nullable String runId(ThinkProcessDocument process) {
        // Re-read: start() wrote it after this document was loaded, and a
        // later turn may be looking at a stale copy.
        String fromDoc = thinkProcessService.findById(process.getId())
                .map(p -> stringParam(p, PARAM_RUN_ID))
                .orElse(null);
        if (fromDoc != null) return fromDoc;
        String local = stringParam(process, PARAM_RUN_ID);
        if (local == null) {
            log.warn("Vogon id='{}' has no run id — the plan never started", process.getId());
        }
        return local;
    }

    /**
     * Caller params for the plan: everything on the process except the
     * fields that address the plan itself.
     */
    private static Map<String, Object> callerParams(ThinkProcessDocument process) {
        Map<String, Object> raw = process.getEngineParams();
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.remove(PARAM_WORKFLOW);
        out.remove(PARAM_WORKFLOW_PATH);
        out.remove(PARAM_RUN_ID);
        return out;
    }

    /**
     * Who the run is started on behalf of — the session's owner, since a
     * process has no user of its own. Falls back to the system marker for
     * a process running in a system session.
     */
    private String startedBy(ThinkProcessDocument process) {
        return sessionService.findBySessionId(process.getSessionId())
                .map(SessionDocument::getUserId)
                .filter(u -> u != null && !u.isBlank())
                .orElse("@system");
    }

    private static @Nullable String stringParam(ThinkProcessDocument process, String key) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) return null;
        Object raw = params.get(key);
        return raw instanceof String s && !s.isBlank() ? s : null;
    }
}
