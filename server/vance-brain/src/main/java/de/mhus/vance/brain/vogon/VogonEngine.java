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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>Which is also why this engine follows the Magrathea switch: it is a
 * binding over the runner, so on a brain where the runner is off there is
 * nothing for it to bind to. Registering anyway would turn a switched-off
 * subsystem into a failure to boot.
 *
 * <p>See {@code specification/public/vogon-engine.md} and
 * {@code planning/vogon-magrathea-merge.md}.
 */
@Component
@ConditionalOnProperty(
        value = "vance.services.magrathea",
        havingValue = "true",
        matchIfMissing = false)
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

    /**
     * {@code none} switches the intake stage off: this plan is never fed from
     * prose, and a missing parameter is a start error rather than a question
     * for a model.
     */
    public static final String PARAM_INTAKE = "intake";

    /** The task text, always passed on so a plan can read it under its own name. */
    public static final String PARAM_TASK = "task";

    private final MagratheaWorkflowService workflowService;
    private final MagratheaStateProjector projector;
    private final MagratheaGateChatAnswerService gateChatAnswerService;
    private final VogonIntake intake;
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

    /**
     * Start the plan now if everything needed is already known, otherwise wait
     * for the task message.
     *
     * <p>A caller that passed the plan and all its required parameters gets the
     * run immediately — no turn, no model, exactly as before. A caller that
     * merely said what they want gets one turn of grace, because the message
     * carrying it is pushed <em>after</em> this method returns
     * ({@code SpawnActionExecutor}) and reading it here would mean reading
     * something that is not there yet.
     */
    @Override
    public void start(ThinkProcessDocument process, ThinkEngineContext ctx) {
        if (deferStart(process)) {
            log.debug("Vogon id='{}' waiting for its task before starting a plan",
                    process.getId());
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
            return;
        }
        beginRun(process, /* taskText */ null);
    }

    /**
     * Can waiting for the task message still change the outcome?
     *
     * <p>Not every unready start is an incomplete one, and the difference
     * matters: deferring a start that is already broken leaves a process
     * standing IDLE forever with no run, no journal and no deadline behind
     * it — the Magrathea watchdog never sees it, because there is no task,
     * and whoever delegated to it waits for something that cannot end. The
     * spec asks for the opposite ({@code vogon-engine.md} §2): a spawn that
     * cannot work fails at once and the process is closed.
     *
     * <p>So deferring happens only where a sentence could still supply the
     * missing piece. It does not happen when
     * {@code params.intake: none} says this plan is never fed from prose,
     * and it does not happen when a plan <em>was</em> declared and does not
     * resolve — a typo in {@code workflow:} or a plan document that fails to
     * parse will not resolve later either.
     */
    private boolean deferStart(ThinkProcessDocument process) {
        if (VogonIntake.INTAKE_NONE.equalsIgnoreCase(stringParam(process, PARAM_INTAKE))) {
            return false;
        }
        Optional<de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow> plan =
                namedPlan(process);
        if (plan.isEmpty()) {
            // Nothing resolved: wait only when nothing was declared either.
            return declaredPath(process) == null && declaredName(process) == null;
        }
        // Resolved: wait only while a required parameter is still missing.
        return !VogonIntake.missingRequired(plan.get(), callerParams(process)).isEmpty();
    }

    /** The plan this process names, by path or by name, if it names one. */
    private Optional<de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow> namedPlan(
            ThinkProcessDocument process) {
        String path = declaredPath(process);
        if (path != null) {
            return intake.loadPlanFromPath(
                    process.getTenantId(), process.getProjectId(), path);
        }
        String name = declaredName(process);
        if (name == null) return Optional.empty();
        return intake.loadPlan(process.getTenantId(), process.getProjectId(), name);
    }

    /**
     * Resolve what is missing, start the run, and remember it.
     *
     * @param taskText what the person asked for, when there is one to read
     */
    private void beginRun(ThinkProcessDocument process, @Nullable String taskText) {
        MagratheaRunBinding binding = bindingFor(process);
        String runId;
        try {
            runId = startRun(process, binding, taskText);
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

    private String startRun(
            ThinkProcessDocument process,
            MagratheaRunBinding binding,
            @Nullable String taskText) {
        Map<String, Object> params = callerParams(process);
        String intakeMode = stringParam(process, PARAM_INTAKE);
        String effectiveTask = taskText != null ? taskText : process.getGoal();

        String path = declaredPath(process);
        String name = declaredName(process);
        var plan = path != null
                ? intake.loadPlanFromPath(process.getTenantId(), process.getProjectId(), path)
                : name == null
                        ? Optional.<de.mhus.vance.shared.magrathea.ResolvedMagratheaWorkflow>empty()
                        : intake.loadPlan(process.getTenantId(), process.getProjectId(), name);

        // A declared plan that does not resolve is a start error, not a
        // question for a model. Falling through here ran the plan-choice
        // stage — a LightLlm call over every resolvable plan — whose answer
        // was then thrown away, because the declared name wins again below;
        // and when the model answered path-shaped, the run started the
        // document *it* picked instead of the one that was declared.
        if ((path != null || name != null) && plan.isEmpty()) {
            throw new IllegalArgumentException(
                    "Vogon cannot resolve the plan it was given: "
                            + (path != null
                                    ? PARAM_WORKFLOW_PATH + "='" + path + "'"
                                    : PARAM_WORKFLOW + "='" + name + "'")
                            + " — no such plan here, or the plan document does not parse.");
        }

        // Only ask about the plan when neither form was declared.
        VogonIntake.Outcome intook = intake.resolve(
                process.getTenantId(), process.getProjectId(),
                plan.orElse(null),
                path != null ? null : name,
                params, effectiveTask, intakeMode);

        Map<String, Object> planParams = withTask(intook.params(), effectiveTask);
        MagratheaRunBinding withOrigin = binding.withDerivedParams(intook.derivedKeys());

        String effectivePath = path != null ? path : intook.planPath();
        if (effectivePath != null) {
            return workflowService.startFromDocument(
                    process.getTenantId(), process.getProjectId(), effectivePath,
                    planParams, startedBy(process), withOrigin);
        }

        String effectiveName = name != null ? name : intook.planName();
        if (effectiveName == null) {
            throw new IllegalArgumentException(
                    "Vogon needs a plan: set engineParams." + PARAM_WORKFLOW
                            + " (name) or " + PARAM_WORKFLOW_PATH + " (document path), "
                            + "or name the plan in the task.");
        }
        return workflowService.start(
                process.getTenantId(), process.getProjectId(), effectiveName,
                planParams, startedBy(process),
                /* parentRun */ null, /* parentState */ null, withOrigin);
    }

    /**
     * The document path this process declares, if any.
     *
     * <p>Read from either param: a {@code workflow:} that carries a slash or a
     * YAML suffix is a path somebody put in the wrong field, and refusing it
     * on that ground would be pedantry — the two fields exist to say how a
     * plan is addressed, and the value already says it.
     */
    private @Nullable String declaredPath(ThinkProcessDocument process) {
        String explicit = stringParam(process, PARAM_WORKFLOW_PATH);
        if (explicit != null) return explicit;
        String named = stringParam(process, PARAM_WORKFLOW);
        return VogonIntake.looksLikePath(named) ? named : null;
    }

    /** The plan name this process declares — never a path (see {@link #declaredPath}). */
    private @Nullable String declaredName(ThinkProcessDocument process) {
        if (stringParam(process, PARAM_WORKFLOW_PATH) != null) return null;
        String named = stringParam(process, PARAM_WORKFLOW);
        return VogonIntake.looksLikePath(named) ? null : named;
    }

    /**
     * The task text also travels as {@code params.task}, so a plan can read what
     * was asked even when it names its own parameters differently.
     */
    private static Map<String, Object> withTask(
            Map<String, Object> params, @Nullable String taskText) {
        if (taskText == null || taskText.isBlank() || params.containsKey(PARAM_TASK)) {
            return params;
        }
        Map<String, Object> out = new LinkedHashMap<>(params);
        out.put(PARAM_TASK, taskText);
        return out;
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

    /**
     * Pauses the run <em>and</em> marks this process suspended.
     *
     * <p>The status write is not bookkeeping: {@code ThinkEngineService}
     * only delegates, so the engine is what makes a suspend visible. A
     * process that pauses its run and keeps its old status stays
     * schedulable inside a SUSPENDED session, and the cascade's re-scan
     * keeps handing it back because it never reaches SUSPENDED.
     */
    @Override
    public void suspend(ThinkProcessDocument process, ThinkEngineContext ctx) {
        withRun(process, runId -> {
            workflowService.pauseRun(process.getTenantId(), process.getProjectId(), runId);
            log.info("Vogon id='{}' paused run '{}'", process.getId(), runId);
        });
        thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.SUSPENDED);
    }

    /**
     * Stops the run <em>and</em> closes this process.
     *
     * <p>Outside {@code withRun} on purpose: a process whose run never
     * started has nothing to stop and still has to close, or
     * {@code process_stop} on it is a no-op that leaves the caller holding
     * a delegation pointer at something nobody will ever finish.
     */
    @Override
    public void stop(ThinkProcessDocument process, ThinkEngineContext ctx) {
        withRun(process, runId -> {
            workflowService.stopRun(process.getTenantId(), process.getProjectId(), runId,
                    "owner process stopped");
            log.info("Vogon id='{}' stopped run '{}'", process.getId(), runId);
        });
        thinkProcessService.closeProcess(process.getId(), CloseReason.STOPPED);
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
     *
     * <p><b>Only a person may answer a gate this way.</b> A
     * {@code UserChatInput} is not proof of one: an orchestrator steering a
     * child through {@code process_message} sends the same type with
     * {@code fromUser = "process:<id>"}, and a trigger spawn sends its
     * source tag. Falling back to the session owner when the sender was not
     * a user meant an agent's "ok" was written into the inbox item stamped
     * as the owner's approval. Whether that person may actually answer
     * <em>this</em> item is decided in {@code MagratheaGateChatAnswerService};
     * here we only make sure a name is not invented.
     */
    private void onUserSaid(ThinkProcessDocument process, SteerMessage.UserChatInput input) {
        String text = input.content();
        if (text == null || text.isBlank()) return;

        String runId = runId(process);
        if (runId == null) {
            // No run yet means start() deferred it: this message is the job.
            // Any sender may deliver that — it is a task, not a decision.
            beginRun(process, text);
            return;
        }

        if (!isHumanSender(input.fromUser())) {
            log.debug("Vogon id='{}' — '{}' is not a person, so it cannot answer a gate",
                    process.getId(), input.fromUser());
            return;
        }
        boolean answered = gateChatAnswerService.tryAnswer(
                process.getTenantId(), runId, text, input.fromUser());
        if (answered) {
            thinkProcessService.updateStatus(process.getId(), ThinkProcessStatus.IDLE);
        } else {
            log.debug("Vogon id='{}' — nothing in run '{}' was waiting for that",
                    process.getId(), runId);
        }
    }

    /**
     * Does {@code fromUser} name a person?
     *
     * <p>The three non-human forms the field can take, all of which reach a
     * Vogon process through the same {@code UserChatInput}: {@code null} or
     * blank (nobody said who), {@code process:<id>} (an orchestrator using
     * {@code process_message}), and the service-account / system markers
     * {@code _name} and {@code @system}. A user name is a
     * {@code UserDocument.name} and is none of those.
     */
    private static boolean isHumanSender(@Nullable String fromUser) {
        if (fromUser == null || fromUser.isBlank()) return false;
        String from = fromUser.trim();
        return !from.startsWith("process:") && !from.startsWith("_") && !from.startsWith("@");
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
            case DONE -> closeAndTellTheParent(process, CloseReason.DONE);
            case FAILED -> closeAndTellTheParent(process, CloseReason.STALE);
            case STOPPED -> closeAndTellTheParent(process, CloseReason.STOPPED);
            default -> log.trace("Vogon id='{}' ignoring run event {}", process.getId(), type);
        }
    }

    /**
     * End this process so that whoever delegated to it finds out.
     *
     * <p>{@code ParentNotificationListener} suppresses the lifecycle event
     * when the parent holds a {@code workerLink} to the child: for a
     * streaming worker that is right, because the news rides the Working WS
     * instead. Vogon streams nothing — it is a runner, not a talker — so for
     * it the suppression means the news rides nothing at all. The parent
     * would keep a delegation pointer aimed at a closed process, and every
     * later thing the person says would go to it.
     *
     * <p>Dropping the link first is the smallest true statement available
     * here: this process is not being watched over a WS, so the ordinary
     * engine path should carry the event — and with it
     * {@link #summarizeForParent}, which is how the result gets home at all.
     *
     * <p>Order matters. The link has to be gone <em>before</em> the close, or
     * the event it triggers is suppressed on its way out.
     */
    private void closeAndTellTheParent(ThinkProcessDocument process, CloseReason reason) {
        String parentId = process.getParentProcessId();
        if (parentId != null && !parentId.isBlank()) {
            try {
                thinkProcessService.removeWorkerLink(parentId, process.getId());
            } catch (RuntimeException ex) {
                log.warn("Vogon id='{}' could not release its parent's watch: {}",
                        process.getId(), ex.toString());
            }
        }
        thinkProcessService.closeProcess(process.getId(), reason);
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
        if (runId == null) {
            log.debug("Vogon id='{}' has no run to control", process.getId());
            return;
        }
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

    /**
     * The run this process owns, or null when it has not started one yet.
     *
     * <p>Silent about the null: before the first turn it is the normal state,
     * not a fault — {@code start()} defers whenever the plan still has to be
     * read out of the task. Callers for which a missing run <em>is</em>
     * surprising say so themselves.
     */
    private @Nullable String runId(ThinkProcessDocument process) {
        // Re-read: the id is written after this document was loaded, and a
        // later turn may be looking at a stale copy.
        String fromDoc = thinkProcessService.findById(process.getId())
                .map(p -> stringParam(p, PARAM_RUN_ID))
                .orElse(null);
        return fromDoc != null ? fromDoc : stringParam(process, PARAM_RUN_ID);
    }

    /**
     * Caller params for the plan: everything on the process except the
     * fields that steer Vogon itself.
     *
     * <p>{@link #PARAM_INTAKE} is one of them: it says how the plan is to be
     * <em>found</em>, not what it runs with. Left in, it travelled into
     * {@code StartRecord.params} and — as soon as a plan happened to declare
     * a parameter called {@code intake} — overwrote that parameter's default
     * with the word {@code none}.
     */
    private static Map<String, Object> callerParams(ThinkProcessDocument process) {
        Map<String, Object> raw = process.getEngineParams();
        if (raw == null || raw.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>(raw);
        out.remove(PARAM_WORKFLOW);
        out.remove(PARAM_WORKFLOW_PATH);
        out.remove(PARAM_RUN_ID);
        out.remove(PARAM_INTAKE);
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
