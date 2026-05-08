package de.mhus.vance.brain.tools.eddie;

import de.mhus.vance.api.eddie.ChannelMode;
import de.mhus.vance.brain.eddie.connection.EddieFrameRouter;
import de.mhus.vance.brain.eddie.connection.EddieWorkerConnection;
import de.mhus.vance.brain.eddie.connection.EddieWorkerConnectionPool;
import de.mhus.vance.shared.eddie.WorkerLinkSnapshot;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Eddie-only: opens a Working-WS to a worker process and registers it
 * as one of Eddie's observed worker links. Eddie uses this when she
 * wants to <i>mirror</i> a worker's live frames (plan updates,
 * stream-tokens, progress) — the steer / spawn paths still go through
 * Mongo via {@code project_chat_send} / {@code project_create}.
 *
 * <p>Effect:
 * <ul>
 *   <li>Resolves the worker {@link ThinkProcessDocument} + its
 *       {@link ProjectDocument} (which carries the home-pod address).</li>
 *   <li>Builds a {@link WorkerLinkSnapshot} with connection identity
 *       and the requested {@link ChannelMode}.</li>
 *   <li>Issues a short-lived JWT for the calling user via
 *       {@link JwtService} (User-identity pass-through — see
 *       {@code engine-message-routing.md} §5.2).</li>
 *   <li>Opens (or reuses) the connection in
 *       {@link EddieWorkerConnectionPool}.</li>
 *   <li>Persists the snapshot on the calling Eddie process via
 *       {@link ThinkProcessService#upsertWorkerLink}.</li>
 * </ul>
 *
 * <p>The tool is idempotent — calling it again with the same
 * {@code processId} just updates the channel-mode and refreshes the
 * snapshot's connection identity. Re-issuing the JWT each call also
 * gives us a clean rotation point on long-lived links.
 *
 * <p>See {@code planning/eddie-plan-mode.md} §2 +
 * {@code specification/eddie-engine.md} §7.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProcessObserveTool implements Tool {

    /** JWT lifetime for the outbound Working-WS. Short — Eddie can re-issue cheaply. */
    private static final Duration JWT_TTL = Duration.ofMinutes(15);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "processId", Map.of(
                            "type", "string",
                            "description", "Worker ThinkProcess id (the value "
                                    + "you got back from project_create or saw "
                                    + "in process_list)."),
                    "channelMode", Map.of(
                            "type", "string",
                            "enum", List.of(
                                    ChannelMode.VERBATIM.name(),
                                    ChannelMode.MILESTONES.name(),
                                    ChannelMode.SUMMARY.name(),
                                    ChannelMode.INBOX.name()),
                            "description", "How worker output should reach the "
                                    + "user. Default: MILESTONES (status "
                                    + "transitions only). Use VERBATIM for "
                                    + "debug, SUMMARY for long reports, INBOX "
                                    + "for things the user wants to read at "
                                    + "leisure.")),
            "required", List.of("processId"));

    private final ThinkProcessService thinkProcessService;
    private final ProjectService projectService;
    private final EddieWorkerConnectionPool connectionPool;
    private final EddieFrameRouter frameRouter;
    private final JwtService jwtService;

    @Override
    public String name() {
        return "process_observe";
    }

    @Override
    public String description() {
        return "Open (or refresh) a live Working-WS to a worker process so "
                + "Eddie sees plan-updates, stream-tokens, and progress as "
                + "they happen. Idempotent — call again to change channelMode.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("eddie", "executive");
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("process_observe requires an Eddie process scope");
        }
        String workerProcessId = stringOrThrow(params, "processId");
        ChannelMode mode = parseChannelMode(params.get("channelMode"));

        ThinkProcessDocument worker = thinkProcessService.findById(workerProcessId)
                .orElseThrow(() -> new ToolException(
                        "Worker process '" + workerProcessId + "' not found"));
        if (!worker.getTenantId().equals(ctx.tenantId())) {
            // Cross-tenant observation is not supported. The pool would
            // happily open the WS, but the JWT we issue is signed with
            // the caller's tenant key — the worker pod would reject it.
            throw new ToolException(
                    "Cross-tenant observation is not allowed (worker tenant differs from caller)");
        }

        ProjectDocument workerProject = projectService.findByTenantAndName(
                worker.getTenantId(), worker.getProjectId())
                .orElseThrow(() -> new ToolException(
                        "Worker project '" + worker.getProjectId() + "' not found"));
        String podAddress = workerProject.getPodIp();
        if (podAddress == null || podAddress.isBlank()) {
            throw new ToolException(
                    "Worker project '" + workerProject.getName()
                            + "' has no claimed home pod yet — cannot observe");
        }

        WorkerLinkSnapshot snapshot = WorkerLinkSnapshot.builder()
                .workerProcessId(worker.getId())
                .workerProcessName(worker.getName())
                .workerProjectName(workerProject.getName())
                .workerSessionId(worker.getSessionId())
                .workerPodAddress(podAddress)
                .channelMode(mode)
                .lastSeen(Instant.now())
                .build();

        // Caller-identity pass-through: short-lived JWT, signed with the
        // tenant's signing key. Worker validates exactly the same way as
        // for a direct user connection.
        String userJwt = jwtService.createToken(
                ctx.tenantId(),
                ctx.userId(),
                Instant.now().plus(JWT_TTL));

        EddieWorkerConnection conn;
        try {
            conn = connectionPool.openOrReuse(
                    ctx.processId(), snapshot, userJwt, frameRouter);
        } catch (RuntimeException e) {
            log.warn("process_observe failed to open WS to worker={} pod={}: {}",
                    worker.getId(), podAddress, e.toString());
            throw new ToolException(
                    "Failed to open observation channel to worker: " + e.getMessage());
        }

        // Persist (or update) the snapshot on the Eddie process so a
        // future pod resume can reconstruct the connection.
        boolean changed = thinkProcessService.upsertWorkerLink(ctx.processId(), snapshot);
        if (!changed) {
            log.debug("process_observe: snapshot upsert returned no-change for caller={} worker={}",
                    ctx.processId(), worker.getId());
        }

        log.info("process_observe: caller={} worker={} mode={} podAddress={}",
                ctx.processId(), worker.getId(), mode, podAddress);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workerProcessId", worker.getId());
        out.put("workerProcessName", worker.getName());
        out.put("workerProjectName", workerProject.getName());
        out.put("channelMode", mode.name());
        out.put("connected", conn != null);
        return out;
    }

    private static String stringOrThrow(Map<String, Object> params, String key) {
        Object raw = params == null ? null : params.get(key);
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new ToolException("Missing or blank '" + key + "'");
        }
        return s;
    }

    private static ChannelMode parseChannelMode(Object raw) {
        if (raw == null) return ChannelMode.MILESTONES;
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return ChannelMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ToolException("Unknown channelMode '" + s
                        + "' — use VERBATIM / MILESTONES / SUMMARY / INBOX");
            }
        }
        throw new ToolException("channelMode must be a string");
    }
}
