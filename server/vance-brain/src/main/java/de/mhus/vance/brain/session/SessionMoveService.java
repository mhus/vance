package de.mhus.vance.brain.session;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.memory.MemoryService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.sessiongroup.SessionGroupService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Moves a session into another project of the <em>same tenant</em> in place —
 * the "Move to project…" action from the Web-UI session list. Unlike
 * {@link SessionDuplicationService} this keeps the {@code sessionId} and
 * rewrites {@code projectId} across the session's collections; nothing is
 * copied.
 *
 * <p><b>Deliberately lossy on memory (v1).</b> The project-bound memory a
 * session accumulated (compaction summaries, scratchpad, RAG contributions)
 * does <em>not</em> travel — RAG and the default memory scope are per-project.
 * Session- and process-scoped {@link de.mhus.vance.shared.memory.MemoryDocument}s
 * would otherwise dangle in the source project, so they are deleted. The user
 * is warned + confirms in the UI before this runs. See
 * {@code planning/session-move.md}.
 *
 * <p><b>Non-running only.</b> A session with a RUNNING think-process is
 * rejected ({@link SessionBusyException}) — a move can imply a home-pod change
 * (pod affinity lives on the project, not the session), so the safe boundary
 * is to move only when no turn is executing on a lane.
 *
 * <p>Not run in a Mongo transaction (mirrors the delete cascade in
 * {@link SessionLifecycleService}): steps are ordered so a mid-way failure
 * never leaves the session visible in the target project without its
 * processes. Each owning service performs its own writes (data ownership per
 * CLAUDE.md) — this service only orchestrates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMoveService {

    private final SessionService sessionService;
    private final ThinkProcessService thinkProcessService;
    private final MemoryService memoryService;
    private final SessionGroupService sessionGroupService;
    private final ProjectService projectService;

    /** Outcome of a move: the new project plus what the cleanup touched. */
    public record MoveResult(
            String sessionId,
            String fromProjectId,
            String toProjectId,
            int processesRetargeted,
            long memoriesDeleted,
            long groupsCleared) {}

    /**
     * Moves {@code sessionId} into {@code targetProjectId}. The caller
     * (controller) has already verified session ownership + tenant scope and
     * enforced {@code CREATE} on the target project.
     *
     * @throws SessionNotFoundException       source session missing / wrong tenant
     * @throws TargetProjectNotFoundException target project absent in this tenant
     * @throws SameProjectException           target equals the current project
     * @throws SessionBusyException           a think-process is RUNNING
     */
    public MoveResult move(String tenantId, String sessionId, String targetProjectId) {
        SessionDocument session = sessionService.findBySessionId(sessionId)
                .filter(s -> tenantId.equals(s.getTenantId()))
                .orElseThrow(() -> new SessionNotFoundException(
                        "Session '" + sessionId + "' not found in tenant '" + tenantId + "'"));

        String fromProjectId = session.getProjectId();
        if (targetProjectId.equals(fromProjectId)) {
            throw new SameProjectException(
                    "Session '" + sessionId + "' is already in project '" + targetProjectId + "'");
        }
        if (!projectService.existsByTenantAndName(tenantId, targetProjectId)) {
            throw new TargetProjectNotFoundException(
                    "Target project '" + targetProjectId + "' not found in tenant '" + tenantId + "'");
        }

        List<ThinkProcessDocument> running = thinkProcessService.findBySessionAndStatus(
                tenantId, sessionId, ThinkProcessStatus.RUNNING);
        if (!running.isEmpty()) {
            throw new SessionBusyException(
                    "Session '" + sessionId + "' has a running process — stop it before moving");
        }

        // Release any live WS binding so no client writes mid-move.
        sessionService.forceUnbind(sessionId);

        // Retarget the durable half: processes first, then the session doc —
        // so a failure never shows the session in the target project without
        // its processes having followed.
        int processes = thinkProcessService.retargetProject(tenantId, sessionId, targetProjectId);
        sessionService.setProjectId(sessionId, targetProjectId);

        // Clean up what stays bound to the source project.
        long memories = memoryService.deleteBySession(tenantId, sessionId);
        long groups = sessionGroupService.removeSessionFromProject(tenantId, fromProjectId, sessionId);

        log.info("Moved session '{}' {}→{} (processes={}, memoriesDropped={}, groupsCleared={})",
                sessionId, fromProjectId, targetProjectId, processes, memories, groups);

        return new MoveResult(
                sessionId, fromProjectId, targetProjectId, processes, memories, groups);
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException(String message) {
            super(message);
        }
    }

    public static class TargetProjectNotFoundException extends RuntimeException {
        public TargetProjectNotFoundException(String message) {
            super(message);
        }
    }

    public static class SameProjectException extends RuntimeException {
        public SameProjectException(String message) {
            super(message);
        }
    }

    public static class SessionBusyException extends RuntimeException {
        public SessionBusyException(String message) {
            super(message);
        }
    }
}
