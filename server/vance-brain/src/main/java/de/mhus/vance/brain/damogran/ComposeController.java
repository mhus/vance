package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final DamogranManifestParser manifestParser;
    private final DocumentService documentService;
    private final SessionService sessionService;
    private final DamogranProcessResolver processResolver;
    private final ComposeRunRegistry runRegistry;
    private final ExecManager execManager;
    private final RequestAuthority authority;

    public ComposeController(DamogranComposeService composeService,
                             DamogranManifestParser manifestParser,
                             DocumentService documentService,
                             SessionService sessionService,
                             DamogranProcessResolver processResolver,
                             ComposeRunRegistry runRegistry,
                             ExecManager execManager,
                             RequestAuthority authority) {
        this.composeService = composeService;
        this.manifestParser = manifestParser;
        this.documentService = documentService;
        this.sessionService = sessionService;
        this.processResolver = processResolver;
        this.runRegistry = runRegistry;
        this.execManager = execManager;
        this.authority = authority;
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
        // Running a compose provisions a workspace and executes tasks in the
        // project — a project-mutating capability, so require WRITE.
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        String yaml = resolveYaml(tenant, projectId, body);
        // Base dir for relative vance: paths: the compose document's directory
        // when run by path, else an explicit composeBasePath (e.g. the Cortex
        // editor / workbook block passes the doc/page folder for inline YAML).
        String baseDir = body.composePath() != null && !body.composePath().isBlank()
                ? DamogranUri.parentDir(body.composePath().trim())
                : (body.composeBasePath() != null && !body.composeBasePath().isBlank()
                        ? body.composeBasePath().trim() : null);

        DamogranManifest manifest;
        String processId;
        try {
            manifest = manifestParser.parse(yaml);
            processId = resolveProcessId(tenant, projectId, manifest, body, httpRequest);
        } catch (DamogranException e) {
            // A malformed manifest (or an un-resolvable agent recipe) is user-authored
            // content, not a protocol error: ride it back in the result envelope so the
            // compose output region renders the reason inline — a bare 400 reaches the
            // client as an opaque "{status:400,error:Bad Request}" blob (Spring drops the
            // ResponseStatusException reason from the body by default).
            log.debug("compose run rejected for {}/{}: {}", tenant, projectId, e.getMessage());
            return errorResult(e.getMessage());
        }

        ComposeRun run = composeService.runAsync(tenant, projectId, processId, manifest, baseDir);
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
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.READ);
        ComposeRun run = runRegistry.find(tenant, projectId, runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "compose run not found: " + runId));
        return renderRun(run, /*includeTail=*/ true);
    }

    /**
     * Cancel an in-flight run: flag it (the runner halts before the next task)
     * and kill the currently-running exec job so a long-running command stops
     * now rather than running to its deadline. Idempotent — a terminal run just
     * returns its final state.
     */
    @PostMapping("/run/{runId}/cancel")
    public Map<String, Object> cancelRun(
            @PathVariable("tenant") String tenant,
            @PathVariable("runId") String runId,
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.WRITE);
        ComposeRun run = runRegistry.find(tenant, projectId, runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "compose run not found: " + runId));
        run.requestCancel();
        String jobId = run.currentExecJobId();
        if (jobId != null) {
            execManager.kill(tenant, projectId, jobId);
        }
        return renderRun(run, /*includeTail=*/ true);
    }

    /**
     * Terminal error envelope for a run that never started (bad manifest /
     * un-resolvable agent recipe) — the same {@code success/error/tasks} shape a
     * failed run returns, at HTTP 200, so the client renders the reason inline
     * rather than as an opaque 4xx body.
     */
    private static Map<String, Object> errorResult(@Nullable String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", false);
        out.put("success", false);
        out.put("status", "failure");
        out.put("error", message != null ? message : "invalid compose manifest");
        out.put("tasks", List.of());
        return out;
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

    /**
     * Live tail of the current exec job, merging stdout <em>and</em> stderr — many
     * long-running tools (training, builds) emit their progress on stderr, so a
     * stdout-only tail would show nothing. Capped to the last {@value #TAIL_LINES}
     * lines across both.
     */
    private List<String> currentTail(ComposeRun run) {
        String jobId = run.currentExecJobId();
        if (jobId == null) {
            return List.of();
        }
        try {
            List<String> out = execManager.tail(
                    run.tenantId(), run.projectId(), jobId, TAIL_LINES, ExecManager.Stream.STDOUT);
            List<String> err = execManager.tail(
                    run.tenantId(), run.projectId(), jobId, TAIL_LINES, ExecManager.Stream.STDERR);
            if (err.isEmpty()) return out;
            if (out.isEmpty()) return err;
            List<String> both = new ArrayList<>(out.size() + err.size());
            both.addAll(out);
            both.addAll(err);
            return both.size() <= TAIL_LINES
                    ? both
                    : both.subList(both.size() - TAIL_LINES, both.size());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /**
     * Resolve the process the compose should run under. When a {@code sessionId}
     * is given and the session belongs to this tenant/project, use its primary
     * chat process (variant a — shared WorkTarget + tool surface with the chat;
     * that process already exists, so no waste).
     *
     * <p>Without a usable chat process the session process is created <b>on
     * demand only</b>: a compose provisions one solely when its {@code session:}
     * section is enabled (it uses {@code spawn} or other process-scoped tooling).
     * The common case — {@code exec}/{@code js}/{@code llm} plus import/export —
     * runs process-less ({@code null}), which keeps the WorkspaceComposeRunner
     * from registering an exec owner and thus avoids waking an idle process with
     * {@code EXEC_FINISHED} events (a wasted LLM turn). Identity: an explicit
     * {@code session.name} (stable across runs — memory continuity), else
     * <b>per app</b> when the caller passes an {@code appKey} (a Workbook app —
     * collaborative, shared workspace), else <b>per (project, user)</b> for a
     * standalone compose file. {@code session.clean} resets it before the run.
     */
    private @Nullable String resolveProcessId(
            String tenant, String projectId, DamogranManifest manifest,
            RunRequest body, HttpServletRequest httpRequest) {
        if (body.sessionId() != null && !body.sessionId().isBlank()) {
            String chatProcess = sessionService.findBySessionId(body.sessionId().trim())
                    .filter(s -> tenant.equals(s.getTenantId()) && projectId.equals(s.getProjectId()))
                    .map(SessionDocument::getChatProcessId)
                    .orElse(null);
            if (chatProcess != null && !chatProcess.isBlank()) {
                return chatProcess;
            }
        }
        DamogranManifest.SessionSpec session = manifest.session();
        if (!session.enabled()) {
            return null;
        }
        String sessionKey = session.name() != null && !session.name().isBlank()
                ? "name:" + session.name().trim()
                : body.appKey() != null && !body.appKey().isBlank()
                        ? "app:" + body.appKey().trim()
                        : "user:" + currentUser(httpRequest);
        return processResolver.resolveComposeSession(
                tenant, projectId, sessionKey, session.recipe(), session.clean());
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
