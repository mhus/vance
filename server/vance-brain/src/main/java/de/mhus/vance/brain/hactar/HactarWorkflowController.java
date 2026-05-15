package de.mhus.vance.brain.hactar;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import de.mhus.vance.api.hactar.HactarParameterDto;
import de.mhus.vance.api.hactar.HactarProcessDto;
import de.mhus.vance.api.hactar.HactarStartRequest;
import de.mhus.vance.api.hactar.HactarWorkflowDto;
import de.mhus.vance.api.hactar.HactarWorkflowSummary;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.hactar.HactarJournalEntry;
import de.mhus.vance.shared.hactar.HactarJournalService;
import de.mhus.vance.shared.hactar.HactarParameterSpec;
import de.mhus.vance.shared.hactar.HactarStateProjector;
import de.mhus.vance.shared.hactar.HactarWorkflowLoader;
import de.mhus.vance.shared.hactar.HactarWorkflowParseException;
import de.mhus.vance.shared.hactar.ResolvedHactarWorkflow;
import de.mhus.vance.shared.hactar.journal.StartRecord;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for Hactar workflows. Three endpoints today:
 *
 * <ul>
 *   <li>{@code POST .../workflows/{name}/start} — start a fresh run,
 *       returns {@code workflowRunId}.</li>
 *   <li>{@code GET  .../workflows/runs/{runId}} — current
 *       {@link HactarProcessDto} snapshot, projected from the
 *       journal.</li>
 *   <li>{@code GET  .../workflows/runs} — list the project's runs,
 *       most recent first. {@code ?workflow=<name>} narrows to one
 *       workflow.</li>
 * </ul>
 *
 * <p>Cancel, event-stream and pagination are deferred — the snapshot
 * endpoint covers the v1 Web-UI "what's happening?" view and is
 * cheap (one journal read).
 */
@RestController
@RequestMapping("/brain/{tenant}/project/{project}")
@ConditionalOnProperty(
        value = "vance.services.hactar",
        havingValue = "true",
        matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class HactarWorkflowController {

    private static final int LIST_LIMIT = 100;

    private final HactarWorkflowService workflowService;
    private final HactarWorkflowLoader workflowLoader;
    private final HactarStateProjector projector;
    private final HactarJournalService journalService;
    private final MongoTemplate mongoTemplate;
    private final RequestAuthority authority;

    // ─── List + detail (definitions) ──────────────────────────────────────

    @GetMapping("/workflows")
    public List<HactarWorkflowSummary> listWorkflows(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        List<ResolvedHactarWorkflow> entries = workflowLoader.listAll(tenant, project);
        List<HactarWorkflowSummary> out = new ArrayList<>(entries.size());
        for (ResolvedHactarWorkflow r : entries) {
            out.add(toSummary(r));
        }
        out.sort(Comparator.comparing(HactarWorkflowSummary::getName));
        return out;
    }

    @GetMapping("/workflows/{name}")
    public HactarWorkflowDto getWorkflow(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        String norm = name.trim().toLowerCase(Locale.ROOT);
        ResolvedHactarWorkflow r = workflowLoader.load(tenant, project, norm)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Workflow '" + norm + "' not found in project '" + project + "'"));
        return toDto(r);
    }

    @PostMapping("/workflows/{name}/start")
    public Map<String, Object> startWorkflow(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @Valid @RequestBody HactarStartRequest body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);

        // startedBy is an audit hint that lands in StartRecord. UI
        // clients (web-face, foot) rarely care about it — fall back to
        // the JWT-authenticated username so callers don't have to thread
        // it through every request, and the audit trail still names a
        // real principal.
        String startedBy = body == null ? null : body.getStartedBy();
        if (startedBy == null || startedBy.isBlank()) {
            Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
            if (u instanceof String s && !s.isBlank()) {
                startedBy = s;
            }
        }

        String runId;
        try {
            runId = workflowService.start(
                    tenant, project, name,
                    body == null ? null : body.getParams(),
                    startedBy);
        } catch (HactarWorkflowService.HactarWorkflowException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (HactarWorkflowParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Workflow YAML invalid: " + ex.getMessage(), ex);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowRunId", runId);
        result.put("workflowName", name);
        return result;
    }

    @GetMapping("/workflows/runs/{runId}")
    public HactarProcessDto getRun(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("runId") String runId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        HactarProcessDto dto = projector.project(runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Workflow run not found: " + runId));
        if (!tenant.equals(dto.getTenantId()) || !project.equals(dto.getProjectId())) {
            // Don't leak run existence to a wrong tenant/project — same shape as 404.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Workflow run not found: " + runId);
        }
        return dto;
    }

    @GetMapping("/workflows/runs")
    public List<HactarProcessDto> listRuns(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @org.springframework.web.bind.annotation.RequestParam(
                    name = "workflow", required = false) String workflowName,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);

        // Find StartRecord entries for this tenant+project — they
        // identify every run in this project. Newest first.
        org.bson.conversions.Bson filter = Filters.and(
                Filters.eq("tenantId", tenant),
                Filters.eq("projectId", project),
                Filters.eq("type", StartRecord.class.getName()));
        Set<String> seen = new HashSet<>();
        List<HactarProcessDto> out = new ArrayList<>();
        for (Document entry : mongoTemplate.getCollection("hactar_journal")
                .find(filter).sort(Sorts.descending("createdAt")).limit(LIST_LIMIT * 2)) {
            String runId = entry.getString("workflowRunId");
            if (runId == null || !seen.add(runId)) continue;
            Optional<HactarProcessDto> dto = projector.project(runId);
            if (dto.isEmpty()) continue;
            if (workflowName != null && !workflowName.equals(dto.get().getWorkflowName())) {
                continue;
            }
            out.add(dto.get());
            if (out.size() >= LIST_LIMIT) break;
        }
        return out;
    }

    // ─── Mappers ──────────────────────────────────────────────────────────

    private static HactarWorkflowSummary toSummary(ResolvedHactarWorkflow r) {
        return HactarWorkflowSummary.builder()
                .name(r.name())
                .description(r.description())
                .version(r.version())
                .source(r.source())
                .paramCount(r.parameters() == null ? 0 : r.parameters().size())
                .stateCount(r.states() == null ? 0 : r.states().size())
                .tags(r.tags() == null || r.tags().isEmpty() ? null : r.tags())
                .build();
    }

    private static HactarWorkflowDto toDto(ResolvedHactarWorkflow r) {
        Map<String, HactarParameterDto> params = null;
        if (r.parameters() != null && !r.parameters().isEmpty()) {
            params = new LinkedHashMap<>();
            for (Map.Entry<String, HactarParameterSpec> e : r.parameters().entrySet()) {
                HactarParameterSpec spec = e.getValue();
                params.put(e.getKey(), HactarParameterDto.builder()
                        .type(spec.type())
                        .required(spec.required())
                        .defaultValue(spec.defaultValue())
                        .build());
            }
        }
        return HactarWorkflowDto.builder()
                .name(r.name())
                .yaml(r.yaml())
                .source(r.source())
                .description(r.description())
                .version(r.version())
                .start(r.startState())
                .parameters(params)
                .allowedTools(r.allowedTools() == null || r.allowedTools().isEmpty()
                        ? null : r.allowedTools())
                .tags(r.tags() == null || r.tags().isEmpty() ? null : r.tags())
                .build();
    }
}
