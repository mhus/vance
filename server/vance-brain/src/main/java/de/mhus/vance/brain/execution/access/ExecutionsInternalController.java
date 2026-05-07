package de.mhus.vance.brain.execution.access;

import de.mhus.vance.api.execution.ExecutionInsightsDto;
import de.mhus.vance.api.execution.ExecutionTailDto;
import de.mhus.vance.brain.execution.ExecutionRegistryEntry;
import de.mhus.vance.brain.execution.ExecutionRegistryService;
import de.mhus.vance.brain.execution.ExecutionRouter;
import de.mhus.vance.brain.execution.ExecutionScopeFilter;
import de.mhus.vance.toolpack.ToolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pod-internal executions endpoints. Reachable only with a valid
 * {@code X-Vance-Internal-Token} (gated by {@code InternalAccessFilter}).
 * Layer 1 ({@link ExecutionsController}) calls these directly on the
 * project's owner pod — no other caller should hit
 * {@code /internal/executions/...}.
 *
 * <p>Tenant/project authorization happened on Layer 1 against the
 * user's JWT; this controller does not re-check it. The two path
 * variables are forwarded straight to the registry so a Layer-1
 * tenant/project boundary survives across the proxy hop.
 */
@RestController
@RequestMapping("/internal/executions/{tenant}/{project}")
@Slf4j
@RequiredArgsConstructor
public class ExecutionsInternalController {

    private final ExecutionRegistryService registry;
    private final ExecutionRouter router;

    @GetMapping("/list")
    public List<ExecutionInsightsDto> list(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @RequestParam(value = "onlyRunning", required = false, defaultValue = "false") boolean onlyRunning,
            @RequestParam(value = "ownerLabel", required = false) String ownerLabel) {
        ExecutionScopeFilter filter = new ExecutionScopeFilter(
                tenant, project, null, null,
                ownerLabel == null || ownerLabel.isBlank() ? null : ownerLabel,
                onlyRunning);
        List<ExecutionRegistryEntry> entries = registry.list(filter);
        List<ExecutionInsightsDto> out = new ArrayList<>(entries.size());
        for (ExecutionRegistryEntry e : entries) out.add(ExecutionInsightsMapper.toDto(e));
        return out;
    }

    /**
     * Stat the entry by id — same registry data the list endpoint
     * surfaces, but as a single record. Returns 404 when the id is
     * unknown or belongs to a different tenant.
     */
    @GetMapping("/{id}/stat")
    public ExecutionInsightsDto stat(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("id") String id) {
        Optional<ExecutionRegistryEntry> hit = registry.find(id);
        if (hit.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown execution: '" + id + "'");
        }
        ExecutionRegistryEntry entry = hit.get();
        if (entry.tenantId() != null && !entry.tenantId().equals(tenant)) {
            // Don't leak existence to the wrong tenant.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown execution: '" + id + "'");
        }
        if (entry.projectId() != null && !entry.projectId().equals(project)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown execution: '" + id + "'");
        }
        return ExecutionInsightsMapper.toDto(entry);
    }

    /**
     * Tail the last N lines of stdout / stderr of an execution. Routes
     * through {@link ExecutionRouter} so foot-side jobs translate into
     * {@code client_exec_tail} dispatches over the WS bridge.
     */
    @GetMapping("/{id}/tail")
    public ExecutionTailDto tail(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("id") String id,
            @RequestParam(value = "n", required = false, defaultValue = "100") int n,
            @RequestParam(value = "stream", required = false, defaultValue = "stdout") String stream) {
        if (n <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'n' must be > 0");
        }
        Map<String, Object> result;
        try {
            result = router.tail(id, tenant, n, stream);
        } catch (ToolException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            HttpStatus status = msg.startsWith("Unknown execution") || msg.contains("not connected")
                    ? HttpStatus.NOT_FOUND
                    : HttpStatus.INTERNAL_SERVER_ERROR;
            throw new ResponseStatusException(status, msg, e);
        }
        @SuppressWarnings("unchecked")
        List<String> lines = result.get("lines") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList()
                : List.of();
        String resolvedStream = result.get("stream") instanceof String s ? s : stream;
        return ExecutionTailDto.builder()
                .id(id)
                .stream(resolvedStream)
                .requested(n)
                .lines(new ArrayList<>(lines))
                .build();
    }

}
