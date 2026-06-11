package de.mhus.vance.brain.python.cortex;

import de.mhus.vance.api.python.PythonExecuteRequest;
import de.mhus.vance.api.python.PythonExecuteResponse;
import de.mhus.vance.api.python.PythonExecutionStatus;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.exec.ExecManager;
import de.mhus.vance.brain.tools.python.PythonExecutionService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for Python script execution from the Cortex Web-UI.
 *
 * <p>The Cortex run button hits {@code POST /python/execute}; the
 * frontend polls {@code GET /python/executions/{id}} for status +
 * stdout/stderr until terminal. Cancellation goes through
 * {@code POST /python/executions/{id}/cancel}.
 *
 * <p>Backed by {@link PythonExecutionService} (RootDir bootstrap +
 * script file write + command build) and {@link ExecManager} (job
 * lifecycle + log buffering). The LLM-facing {@code execute_python}
 * tool shares the same {@link PythonExecutionService} so both paths
 * converge on identical workspace state.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class PythonCortexController {

    private final PythonExecutionService pythonExecutionService;
    private final ExecManager execManager;
    private final DocumentService documentService;
    private final RequestAuthority authority;

    @PostMapping("/brain/{tenant}/python/execute")
    public PythonExecuteResponse execute(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam(value = "sessionId", required = false) @Nullable String sessionId,
            @RequestBody PythonExecuteRequest request,
            HttpServletRequest httpRequest) {

        String code;
        @Nullable String resolvedProjectId = projectId;
        if (request.getScriptId() != null && !request.getScriptId().isBlank()) {
            DocumentDocument doc = loadOwned(tenant, request.getScriptId());
            authority.enforce(httpRequest,
                    new Resource.Document(tenant, doc.getProjectId(), doc.getPath()),
                    Action.EXECUTE);
            code = documentService.readContent(doc);
            if (resolvedProjectId == null) resolvedProjectId = doc.getProjectId();
        } else if (request.getCode() != null && !request.getCode().isBlank()) {
            code = request.getCode();
            if (resolvedProjectId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "projectId is required when executing inline code");
            }
            authority.enforce(httpRequest,
                    new Resource.Project(tenant, resolvedProjectId), Action.EXECUTE);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either scriptId or code must be supplied");
        }

        List<String> args = request.getArgs() == null ? List.of() : request.getArgs();
        String executionId = pythonExecutionService.executeAsync(
                tenant,
                resolvedProjectId,
                sessionId,
                null,
                code,
                args,
                request.getFlags());
        return PythonExecuteResponse.builder().executionId(executionId).build();
    }

    @GetMapping("/brain/{tenant}/python/executions/{executionId}")
    public PythonExecutionStatus status(
            @PathVariable("tenant") String tenant,
            @PathVariable("executionId") String executionId,
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Project(tenant, projectId), Action.READ);
        Optional<Map<String, Object>> snap = execManager.renderJob(tenant, projectId, executionId);
        return snap
                .map(m -> toStatus(executionId, m))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown executionId: " + executionId));
    }

    @PostMapping("/brain/{tenant}/python/executions/{executionId}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable("tenant") String tenant,
            @PathVariable("executionId") String executionId,
            @RequestParam("projectId") String projectId,
            HttpServletRequest httpRequest) {

        authority.enforce(httpRequest,
                new Resource.Project(tenant, projectId), Action.EXECUTE);
        boolean ok = execManager.kill(tenant, projectId, executionId);
        return ok
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    // ─── Helpers ────────────────────────────────────────────────────

    /**
     * Map the {@link ExecManager#renderJob} output to the
     * Cortex-frontend's {@link PythonExecutionStatus} shape. The
     * underlying {@code ExecJob.Status} enum names are mapped to the
     * lowercase {@code RunState} the Cortex runner UI uses.
     */
    private PythonExecutionStatus toStatus(String executionId, Map<String, Object> snap) {
        String rawStatus = String.valueOf(snap.get("status"));
        Integer exitCode = (Integer) snap.get("exitCode");
        String state = mapState(rawStatus, exitCode);
        @Nullable String errorMessage = null;
        if ("failed".equals(state)) {
            if (exitCode != null && exitCode != 0) {
                errorMessage = "Process exited with code " + exitCode;
            } else if ("ORPHANED".equals(rawStatus)) {
                errorMessage = "Worker thread orphaned the job";
            }
        }
        Object durRaw = snap.get("durationMs");
        Long durationMs = durRaw instanceof Number n ? n.longValue() : null;
        return PythonExecutionStatus.builder()
                .executionId(executionId)
                .state(state)
                .exitCode(exitCode)
                .durationMs(durationMs)
                .stdout(String.valueOf(snap.getOrDefault("stdout", "")))
                .stderr(String.valueOf(snap.getOrDefault("stderr", "")))
                .truncated(Boolean.TRUE.equals(snap.get("truncated")))
                .errorMessage(errorMessage)
                .build();
    }

    private String mapState(String rawStatus, @Nullable Integer exitCode) {
        return switch (rawStatus) {
            case "RUNNING" -> "running";
            case "COMPLETED" -> (exitCode != null && exitCode == 0) ? "finished" : "failed";
            case "FAILED", "ORPHANED" -> "failed";
            case "KILLED" -> "cancelled";
            default -> "running";
        };
    }

    private DocumentDocument loadOwned(String tenant, String id) {
        DocumentDocument doc = documentService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!tenant.equals(doc.getTenantId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return doc;
    }
}
