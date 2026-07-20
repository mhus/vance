package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for running a Damogran compose from the Web-UI (a "Run" button
 * in the Cortex {@code compose}-kind editor), without going through the LLM
 * tool path.
 *
 * <p>Single endpoint {@code POST /brain/{tenant}/compose/run} — takes a
 * {@code composePath} (document) or inline {@code composeYaml} plus a
 * {@code projectId}, delegates to {@link DamogranComposeService}, and returns
 * the per-task result with produced outputs (as {@code vance-workspace:} URIs).
 *
 * <p>Behind the regular Vance access filter (user JWT). When the caller passes
 * the active cortex {@code sessionId}, the run binds to that session's primary
 * chat process — the compose sets <em>its</em> WorkTarget, so the workspace is
 * shared with what the user does in the chat (variant a). Without a session (or
 * a session that has no chat process yet), it binds to the project carrier.
 *
 * <p>Runs are async: {@code POST /run} starts a background run and waits a
 * fast-path budget ({@value #FAST_PATH_WAIT_MS}ms) — quick composes return their
 * result inline, longer ones come back with a {@code runId} that the client
 * polls via {@code GET /run/{runId}} (status + live tail of the current exec).
 */
@RestController
@RequestMapping("/brain/{tenant}/compose")
@Slf4j
public class ComposeController {

    /** Fast-path: how long POST blocks for a quick result before handing back a runId. */
    private static final long FAST_PATH_WAIT_MS = 30_000;
    private static final int TAIL_LINES = 40;

    private final DamogranComposeService composeService;
    private final DocumentService documentService;
    private final SessionService sessionService;
    private final DamogranProcessResolver processResolver;
    private final ComposeRunRegistry runRegistry;
    private final ExecManager execManager;

    public ComposeController(DamogranComposeService composeService,
                             DocumentService documentService,
                             SessionService sessionService,
                             DamogranProcessResolver processResolver,
                             ComposeRunRegistry runRegistry,
                             ExecManager execManager) {
        this.composeService = composeService;
        this.documentService = documentService;
        this.sessionService = sessionService;
        this.processResolver = processResolver;
        this.runRegistry = runRegistry;
        this.execManager = execManager;
    }

    @PostMapping("/run")
    public Map<String, Object> run(
            @PathVariable("tenant") String tenant,
            @RequestBody RunRequest body,
            HttpServletRequest httpRequest) {

        if (body.projectId() == null || body.projectId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'projectId' is required");
        }
        String projectId = body.projectId().trim();
        String yaml = resolveYaml(tenant, projectId, body);
        // Base dir for relative vance: paths: the compose document's directory
        // when run by path, else an explicit composeBasePath (e.g. the Cortex
        // editor / workbook block passes the doc/page folder for inline YAML).
        String baseDir = body.composePath() != null && !body.composePath().isBlank()
                ? DamogranUri.parentDir(body.composePath().trim())
                : (body.composeBasePath() != null && !body.composeBasePath().isBlank()
                        ? body.composeBasePath().trim() : null);

        String processId = resolveProcessId(tenant, projectId, body, httpRequest);

        ComposeRun run;
        try {
            run = composeService.runAsync(tenant, projectId, processId, yaml, baseDir);
        } catch (DamogranException e) {
            // Synchronous failures (manifest parse) still surface as 400.
            log.debug("compose run rejected for {}/{}: {}", tenant, projectId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            run.awaitDone(FAST_PATH_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return renderRun(run, /*includeTail=*/ false);
    }

    /** Poll an async run's status (+ live tail of the current exec) by id. */
    @GetMapping("/run/{runId}")
    public Map<String, Object> runStatus(
            @PathVariable("tenant") String tenant,
            @PathVariable("runId") String runId,
            @RequestParam("projectId") String projectId) {
        ComposeRun run = runRegistry.find(tenant, projectId, runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "compose run not found: " + runId));
        return renderRun(run, /*includeTail=*/ true);
    }

    /**
     * Response for a run: the final result shape (tasks + outputs) when terminal,
     * else {@code running} + current-task + (on poll) the live exec tail.
     */
    private Map<String, Object> renderRun(ComposeRun run, boolean includeTail) {
        boolean running = run.status() == ComposeRun.Status.RUNNING;
        Map<String, Object> out = (!running && run.result() != null)
                ? new LinkedHashMap<>(DamogranResponse.toMap(run.result()))
                : new LinkedHashMap<>();
        out.put("runId", run.runId());
        out.put("running", running);
        out.put("status", run.status().name().toLowerCase(java.util.Locale.ROOT));
        out.put("workspace", run.workspaceName());
        if (running) {
            out.put("currentTaskIndex", run.currentTaskIndex());
            if (run.currentTaskType() != null) {
                out.put("currentTaskType", run.currentTaskType());
            }
            if (includeTail) {
                List<String> tail = currentTail(run);
                if (!tail.isEmpty()) {
                    out.put("tail", tail);
                }
            }
        } else if (run.result() == null && run.error() != null) {
            out.put("error", run.error());
        }
        return out;
    }

    private List<String> currentTail(ComposeRun run) {
        String jobId = run.currentExecJobId();
        if (jobId == null) {
            return List.of();
        }
        try {
            return execManager.tail(run.tenantId(), run.projectId(), jobId,
                    TAIL_LINES, ExecManager.Stream.STDOUT);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Resolve the process the compose should run under. When a {@code sessionId}
     * is given and the session belongs to this tenant/project, use its primary
     * chat process (variant a — shared WorkTarget + tool surface with the chat).
     * Otherwise (no session, foreign session, or no chat process yet) bind to a
     * chatless carrier process so scripts still reach the workspace via the file
     * tools. Carrier scope: <b>per app</b> when the caller passes an
     * {@code appKey} (a Workbook app — collaborative, shared workspace), else
     * <b>per (project, user)</b> for a standalone compose file — a user running
     * several compose files in sequence shares one carrier, distinct per user.
     */
    private String resolveProcessId(
            String tenant, String projectId, RunRequest body, HttpServletRequest httpRequest) {
        if (body.sessionId() != null && !body.sessionId().isBlank()) {
            String chatProcess = sessionService.findBySessionId(body.sessionId().trim())
                    .filter(s -> tenant.equals(s.getTenantId()) && projectId.equals(s.getProjectId()))
                    .map(SessionDocument::getChatProcessId)
                    .orElse(null);
            if (chatProcess != null && !chatProcess.isBlank()) {
                return chatProcess;
            }
        }
        String carrierKey = body.appKey() != null && !body.appKey().isBlank()
                ? "app:" + body.appKey().trim()
                : "user:" + currentUser(httpRequest);
        return processResolver.resolveComposeCarrier(tenant, projectId, carrierKey);
    }

    private static String currentUser(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (u instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
    }

    private String resolveYaml(String tenant, String projectId, RunRequest body) {
        if (body.composeYaml() != null && !body.composeYaml().isBlank()) {
            return body.composeYaml();
        }
        if (body.composePath() != null && !body.composePath().isBlank()) {
            DocumentDocument doc = documentService
                    .findByPath(tenant, projectId, body.composePath().trim())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "compose document not found: " + body.composePath()));
            try (InputStream in = documentService.loadContent(doc)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to read compose document: " + e.getMessage());
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "'composePath' or 'composeYaml' is required");
    }

    /** Request body for the run endpoint. */
    public record RunRequest(
            @Nullable String composePath,
            @Nullable String composeYaml,
            @Nullable String composeBasePath,
            @Nullable String projectId,
            @Nullable String sessionId,
            /** App identity (Workbook app folder) → per-app chatless carrier; null = per-user. */
            @Nullable String appKey) {}
}
