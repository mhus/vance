package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunChildDto;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunLinkDto;
import de.mhus.vance.api.runs.RunStatus;
import de.mhus.vance.api.runs.RunStepDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.api.thinkprocess.CloseReason;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.thinkengine.ThinkEngine;
import de.mhus.vance.brain.thinkengine.ThinkEngineService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan-shaped ThinkProcesses as runs — Vogon phases, Marvin task trees.
 *
 * <p>Which engines qualify is not decided here: each engine answers
 * {@link ThinkEngine#planShaped()} for itself. A name list in this class
 * would be one more thing to forget when an engine is added, and it would
 * put the question in the wrong place — whether there is a plan to show
 * is a property of the engine, not of the view.
 *
 * <p>What is left here is Marvin. Vogon used to be read from a
 * {@code strategyState} on the process; it now runs its plan as a
 * workflow, so its steps live in the journal and {@code MagratheaRunSource}
 * shows them — this source would otherwise list the same run twice, once
 * as phases and once as states.
 *
 * <p>Marvin's task tree is not read yet: the summary and status are
 * accurate, the step list is empty. That gap predates the move and is
 * tracked as its own piece of work — reading a tree is not reading a
 * flat phase list, and pretending otherwise here would be worse than an
 * honest blank.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ThinkProcessRunSource implements RunSource {

    public static final String SOURCE_ID = "process";

    private final ThinkProcessService thinkProcessService;
    private final ThinkEngineService thinkEngineService;
    private final ObjectMapper objectMapper;
    /** Owns pause/resume/stop for processes; the WS handlers use the same. */
    private final de.mhus.vance.brain.session.SessionLifecycleService sessionLifecycle;
    private final de.mhus.vance.brain.thinkengine.ProcessEventEmitter processEventEmitter;
    /** Whose session a process belongs to — see visibleTo. */
    private final de.mhus.vance.shared.session.SessionService sessionService;
    private final de.mhus.vance.shared.permission.PermissionService permissionService;

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<RunSummaryDto> list(String tenantId, String projectId, int limit) {
        // Filtered in the query, not afterwards. Most of a busy project's
        // processes are chat turns, so filtering in Java forced an
        // over-fetch factor picked by guesswork — too small and the list
        // came back short, too large and one page view loaded thousands of
        // documents. The engine set is known here, so Mongo can do it.
        List<RunSummaryDto> out = new ArrayList<>();
        for (ThinkProcessDocument p : thinkProcessService.findByProjectAndEngines(
                tenantId, projectId, planShapedEngines(), limit)) {
            out.add(toSummary(p));
        }
        return out;
    }

    /**
     * Names of the registered engines that declare {@link
     * ThinkEngine#planShaped()}. Recomputed per call — the registry is a
     * small in-memory map, and caching it would mean a stale answer on the
     * one occasion it changes.
     */
    private Set<String> planShapedEngines() {
        Set<String> names = new LinkedHashSet<>();
        for (String name : thinkEngineService.listEngines()) {
            if (thinkEngineService.resolve(name).map(ThinkEngine::planShaped).orElse(false)) {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public Optional<RunDetailDto> get(String tenantId, String projectId, String nativeId) {
        Optional<ThinkProcessDocument> found = load(tenantId, projectId, nativeId);
        if (found.isEmpty()) return Optional.empty();
        ThinkProcessDocument process = found.get();

        return Optional.of(RunDetailDto.builder()
                .summary(toSummary(process))
                .steps(List.of())
                .variables(new LinkedHashMap<>())
                .children(List.of())
                .links(List.of(RunLinkDto.builder()
                        .rel("session").label(process.getSessionId())
                        .target(process.getSessionId()).build()))
                .waitingOnInboxItemId(null)
                .allowedActions(actionsFor(process))
                .extra(Map.of(
                        "engine", process.getThinkEngine(),
                        "recipe", process.getRecipeName() == null ? "" : process.getRecipeName(),
                        "goal", process.getGoal() == null ? "" : process.getGoal()))
                .build());
    }

    @Override
    public Set<RunAction> allowedActions(String tenantId, String projectId, String nativeId) {
        return load(tenantId, projectId, nativeId)
                .map(ThinkProcessRunSource::actionsFor)
                .orElseGet(Set::of);
    }

    /**
     * What a process in this state can be asked to do. Derived from the
     * status rather than declared per source, so a finished run offers
     * nothing and the UI needs no rule of its own.
     *
     * <p>{@code SUSPENDED} is deliberately not resumable here: that hold
     * belongs to the session, and offering a second owner for the same
     * state is how a state ends up flapping.
     *
     * <p>{@code IDLE} and {@code BLOCKED} offer no {@code PAUSE} either,
     * and for a plainer reason: {@code SessionLifecycleService.pauseProcess}
     * only pauses what is interruptible and reports {@code false} for those
     * two. A button that is rendered, pressed, and then does nothing is
     * worse than an absent one — and it is not the same thing as a refusal,
     * which is what a run somebody may not touch answers.
     */
    private static Set<RunAction> actionsFor(ThinkProcessDocument process) {
        return switch (process.getStatus()) {
            case INIT, RUNNING -> Set.of(RunAction.PAUSE, RunAction.STOP);
            case IDLE, BLOCKED -> Set.of(RunAction.STOP);
            case PAUSED -> Set.of(RunAction.RESUME, RunAction.STOP);
            case SUSPENDED -> Set.of(RunAction.STOP);
            case CLOSED -> Set.of();
        };
    }

    @Override
    public void perform(String tenantId, String projectId, String nativeId,
                        RunAction action, String reason) {
        ThinkProcessDocument process = load(tenantId, projectId, nativeId)
                .orElseThrow(() -> new IllegalArgumentException("No such run: " + nativeId));
        // Idempotent by construction: an action the current state does not
        // offer is a no-op, not an error — the button may have been
        // rendered from a snapshot that has since moved on.
        if (!actionsFor(process).contains(action)) {
            log.debug("Run action {} not applicable to process '{}' in state {}",
                    action, nativeId, process.getStatus());
            return;
        }
        switch (action) {
            case PAUSE -> sessionLifecycle.pauseProcess(process);
            case RESUME -> sessionLifecycle.resumeProcess(process, processEventEmitter);
            case STOP -> sessionLifecycle.stopProcess(process);
        }
        log.info("Run action {} performed on process '{}' (reason: {})", action, nativeId, reason);
    }

    /** The process, but only if it belongs to the caller's scope. */
    private Optional<ThinkProcessDocument> load(String tenantId, String projectId, String nativeId) {
        return thinkProcessService.findById(nativeId)
                .filter(p -> tenantId.equals(p.getTenantId()) && projectId.equals(p.getProjectId()));
    }

    private RunSummaryDto toSummary(ThinkProcessDocument process) {
        return RunSummaryDto.builder()
                .runId(RunId.of(SOURCE_ID, process.getId()).composite())
                .source(SOURCE_ID)
                .name(process.getTitle() != null && !process.getTitle().isBlank()
                        ? process.getTitle() : process.getName())
                .status(mapStatus(process.getStatus(), process.getCloseReason()))
                .step(null)
                .projectId(process.getProjectId())
                .startedBy(process.getRecipeName())
                .startedAt(process.getCreatedAt())
                .updatedAt(process.getUpdatedAt())
                .parentRunId(process.getParentProcessId() == null
                        ? null : RunId.of(SOURCE_ID, process.getParentProcessId()).composite())
                .build();
    }

    /**
     * Seven statuses and eight close reasons onto six. The lossy part is
     * intentional: {@code IDLE} and {@code BLOCKED} both mean "waiting for
     * something outside", and {@code SUSPENDED} is a hold like a pause even
     * though its owner is the session rather than the user.
     */
    private static RunStatus mapStatus(
            @Nullable ThinkProcessStatus status, @Nullable CloseReason closeReason) {
        if (status == null) return RunStatus.RUNNING;
        return switch (status) {
            case INIT, RUNNING -> RunStatus.RUNNING;
            case IDLE, BLOCKED -> RunStatus.WAITING;
            case PAUSED, SUSPENDED -> RunStatus.PAUSED;
            case CLOSED -> mapCloseReason(closeReason);
        };
    }

    private static RunStatus mapCloseReason(@Nullable CloseReason reason) {
        if (reason == null) return RunStatus.FAILED;
        return switch (reason) {
            case DONE, AUTO_CLOSE -> RunStatus.DONE;
            case INCOMPLETE, STALE -> RunStatus.FAILED;
            case STOPPED, ARCHIVED, USER_DELETE, ABANDONED -> RunStatus.STOPPED;
        };
    }

    /**
     * A process belongs to a person through its session, which is narrower
     * than the project the caller was checked against — so the same rule
     * {@code MagratheaRunSource} applies to a bound run applies here: the
     * session is a system one (scheduler, agrajag — nobody's conversation),
     * the caller owns it, or the caller administers the project.
     *
     * <p>Without this the read side hands a project READER the {@code goal}
     * of every worker somebody else dictated into their chat, and the write
     * side stops their runs. A missing process is reported visible on
     * purpose — "no such run" is then answered once, by {@code get} and
     * {@code perform}, rather than twice with two different meanings.
     */
    @Override
    public boolean visibleTo(
            de.mhus.vance.shared.permission.SecurityContext subject,
            String tenantId, String projectId, String nativeId) {
        Optional<ThinkProcessDocument> found = load(tenantId, projectId, nativeId);
        if (found.isEmpty()) return true;
        String sessionId = found.get().getSessionId();
        if (sessionId == null || sessionId.isBlank()) return true;

        return sessionService.findBySessionId(sessionId)
                .map(session -> session.isSystem()
                        || (subject != null
                            && subject.subjectId().equals(session.getUserId()))
                        || permissionService.check(subject,
                                new de.mhus.vance.shared.permission.Resource.Project(
                                        tenantId, projectId),
                                de.mhus.vance.shared.permission.Action.ADMIN))
                .orElse(true);
    }
}
