package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 * a session that has no chat process yet), it runs process-less as before —
 * {@code spawn} tasks then stay unavailable via this path.
 */
@RestController
@RequestMapping("/brain/{tenant}/compose")
@Slf4j
public class ComposeController {

    private final DamogranComposeService composeService;
    private final DocumentService documentService;
    private final SessionService sessionService;
    private final DamogranProcessResolver processResolver;

    public ComposeController(DamogranComposeService composeService,
                             DocumentService documentService,
                             SessionService sessionService,
                             DamogranProcessResolver processResolver) {
        this.composeService = composeService;
        this.documentService = documentService;
        this.sessionService = sessionService;
        this.processResolver = processResolver;
    }

    @PostMapping("/run")
    public Map<String, Object> run(
            @PathVariable("tenant") String tenant,
            @RequestBody RunRequest body) {

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

        String processId = resolveProcessId(tenant, projectId, body);

        try {
            DamogranComposeResult result = composeService.run(tenant, projectId, processId, yaml, baseDir);
            return DamogranResponse.toMap(result);
        } catch (DamogranException e) {
            log.debug("compose run failed for {}/{}: {}", tenant, projectId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Resolve the process the compose should run under. When a {@code sessionId}
     * is given and the session belongs to this tenant/project, use its primary
     * chat process (variant a — shared WorkTarget + tool surface with the chat).
     * Otherwise (no session, foreign session, or no chat process yet) bind to the
     * project's chatless carrier process, so scripts still reach the workspace
     * via the file tools.
     */
    private String resolveProcessId(String tenant, String projectId, RunRequest body) {
        if (body.sessionId() != null && !body.sessionId().isBlank()) {
            String chatProcess = sessionService.findBySessionId(body.sessionId().trim())
                    .filter(s -> tenant.equals(s.getTenantId()) && projectId.equals(s.getProjectId()))
                    .map(SessionDocument::getChatProcessId)
                    .orElse(null);
            if (chatProcess != null && !chatProcess.isBlank()) {
                return chatProcess;
            }
        }
        return processResolver.resolveProjectComposeProcess(tenant, projectId);
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
            @Nullable String sessionId) {}
}
