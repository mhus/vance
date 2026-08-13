package de.mhus.vance.brain.runs;

import de.mhus.vance.api.runs.RunAction;
import de.mhus.vance.api.runs.RunDetailDto;
import de.mhus.vance.api.runs.RunSummaryDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read surface of the run view: the instances of every runtime that has
 * them, under one address.
 *
 * <p>Project-scoped, because that is the axis both sources share — a
 * Magrathea run belongs to a project outright, a ThinkProcess through its
 * session. Authorisation is checked here at project level and again
 * inside each source for its own resource; the facade deliberately does
 * not merge the two checks.
 */
@RestController
@RequestMapping("/brain/{tenant}/runs")
@RequiredArgsConstructor
public class RunController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final RunSourceRegistry registry;
    private final RequestAuthority authority;

    /** Runs of one project, newest first, merged across sources. */
    @GetMapping
    public List<RunSummaryDto> list(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            @RequestParam(name = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        int effective = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(1, limit), MAX_LIMIT);
        return registry.list(tenant, projectId, effective);
    }

    /**
     * One run by composite id ({@code <source>:<id>}). A run of another
     * project answers 404 rather than 403 — the same shape the workflow
     * controller uses, so the endpoint cannot be used to probe for
     * existence.
     */
    @GetMapping("/{runId}")
    public RunDetailDto get(
            @PathVariable("tenant") String tenant,
            @PathVariable("runId") String runId,
            @RequestParam("projectId") String projectId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return registry.get(tenant, projectId, runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Run not found: " + runId));
    }

    /**
     * Perform an action on a run.
     *
     * <p>{@code Project WRITE}, not {@code READ}: this changes something.
     * An action the run does not currently offer is a no-op rather than a
     * 409 — the button was rendered from a snapshot, and by the time the
     * click arrives the run may legitimately have moved on.
     */
    @PostMapping("/{runId}/actions/{action}")
    public RunDetailDto perform(
            @PathVariable("tenant") String tenant,
            @PathVariable("runId") String runId,
            @PathVariable("action") String action,
            @RequestParam("projectId") String projectId,
            @RequestParam(name = "reason", required = false) String reason,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.WRITE);

        RunAction parsed;
        try {
            parsed = RunAction.valueOf(action.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown run action: " + action);
        }
        try {
            registry.perform(tenant, projectId, runId, parsed,
                    reason == null || reason.isBlank() ? "run view" : reason);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), ex);
        }
        // Hand back the fresh state so the caller renders from truth
        // rather than from what it assumed the action would do.
        return registry.get(tenant, projectId, runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Run not found: " + runId));
    }

    /** Which sources are active — lets the UI build its filter from data. */
    @GetMapping("/sources")
    public List<String> sources(
            @PathVariable("tenant") String tenant,
            @RequestParam("projectId") String projectId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.READ);
        return registry.sourceIds();
    }
}
