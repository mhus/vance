package de.mhus.vance.brain.damogran;

import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
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
 * <p>Behind the regular Vance access filter (user JWT). Runs without a process
 * context, so the {@code spawn} task type is unavailable via this path (use the
 * {@code compose_run} tool from a chat process for spawns).
 */
@RestController
@RequestMapping("/brain/{tenant}/compose")
@Slf4j
public class ComposeController {

    private final DamogranComposeService composeService;
    private final DocumentService documentService;

    public ComposeController(DamogranComposeService composeService, DocumentService documentService) {
        this.composeService = composeService;
        this.documentService = documentService;
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

        try {
            DamogranComposeResult result = composeService.run(tenant, projectId, null, yaml);
            return DamogranResponse.toMap(result);
        } catch (DamogranException e) {
            log.debug("compose run failed for {}/{}: {}", tenant, projectId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
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
            @Nullable String projectId) {}
}
