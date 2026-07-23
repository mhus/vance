package de.mhus.vance.brain.ursascheduler;

import de.mhus.vance.api.eventlog.EventLogEntryDto;
import de.mhus.vance.api.eventlog.EventType;
import de.mhus.vance.api.ursascheduler.LockMode;
import de.mhus.vance.api.ursascheduler.OverlapPolicy;
import de.mhus.vance.api.ursascheduler.SchedulerDto;
import de.mhus.vance.api.ursascheduler.SchedulerSaveRequest;
import de.mhus.vance.api.ursascheduler.SchedulerSource;
import de.mhus.vance.api.ursascheduler.SchedulerSummary;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.eventlog.EventLogDocument;
import de.mhus.vance.shared.eventlog.EventLogService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.ursascheduler.ResolvedUrsaScheduler;
import de.mhus.vance.shared.ursascheduler.UrsaSchedulerLoader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST surface for the Web-UI scheduler editor. CRUD lives at
 * {@code /brain/{tenant}/project/{project}/scheduler}; event-log
 * pagination at {@code .../scheduler/{name}/events}; a force-refresh
 * trigger at {@code .../scheduler/refresh}.
 *
 * <p>All endpoints are tenant- and project-scoped; the tenant in the
 * path is validated against the JWT {@code tid} claim by
 * {@code BrainAccessFilter} before requests reach the controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/project/{project}")
@RequiredArgsConstructor
@Slf4j
public class UrsaSchedulerController {

    private final UrsaSchedulerLoader loader;
    private final UrsaSchedulerService schedulerService;
    private final DocumentService documentService;
    private final EventLogService eventLogService;
    private final RequestAuthority authority;

    // ─── List ─────────────────────────────────────────────────────────────

    @GetMapping("/scheduler")
    public List<SchedulerSummary> listSchedulers(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        List<ResolvedUrsaScheduler> entries = loader.listAll(tenant, project);
        List<SchedulerSummary> out = new ArrayList<>(entries.size());
        for (ResolvedUrsaScheduler r : entries) {
            out.add(toSummary(tenant, project, r));
        }
        out.sort(Comparator.comparing(SchedulerSummary::getName));
        return out;
    }

    // ─── Get ──────────────────────────────────────────────────────────────

    @GetMapping("/scheduler/{name}")
    public SchedulerDto getScheduler(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        String norm = normalizeName(name);
        ResolvedUrsaScheduler r = loader.load(tenant, project, norm)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Scheduler '" + norm + "' not found in project '" + project + "'"));
        return toDto(r);
    }

    // ─── Save (create/update) ─────────────────────────────────────────────

    @PutMapping("/scheduler/{name}")
    public ResponseEntity<SchedulerDto> saveScheduler(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @Valid @RequestBody SchedulerSaveRequest body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        String norm = normalizeName(name);
        String yaml = body.getYaml();
        if (yaml == null || yaml.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'yaml' must be a non-empty string");
        }
        try {
            loader.validateYaml(norm, yaml);
        } catch (UrsaSchedulerLoader.SchedulerParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        String path = pathFor(norm);
        Optional<DocumentDocument> existing = documentService.findByPath(tenant, project, path);
        boolean created = existing.isEmpty();
        String createdBy = authority.contextOf(request).subjectId();
        if (existing.isPresent()) {
            documentService.update(existing.get().getId(),
                    /*newTitle*/ null,
                    /*newTags*/ null,
                    /*newInlineText*/ yaml,
                    /*newPath*/ null,
                    systemActor(request));
        } else {
            documentService.createText(tenant, project, path,
                    "Scheduler: " + norm,
                    /*tags*/ null,
                    yaml,
                    createdBy,
                    systemActor(request));
        }
        // refreshOne is driven by the DocumentChangedEvent →
        // UrsaSchedulerDocumentListener chain that documentService
        // already kicked off above.
        ResolvedUrsaScheduler reloaded = loader.load(tenant, project, norm)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Scheduler vanished immediately after write"));
        SchedulerDto dto = toDto(reloaded);
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).body(dto);
    }

    // ─── Delete ───────────────────────────────────────────────────────────

    @DeleteMapping("/scheduler/{name}")
    public ResponseEntity<Void> deleteScheduler(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        String norm = normalizeName(name);
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenant, project, pathFor(norm));
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        documentService.delete(existing.get().getId(), systemActor(request));
        // Refresh travels via the DocumentChangedEvent → listener chain.
        return ResponseEntity.noContent().build();
    }

    // ─── Refresh ──────────────────────────────────────────────────────────

    @PostMapping("/scheduler/refresh")
    public RefreshResult refresh(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        int registered = schedulerService.refresh(tenant, project);
        return new RefreshResult(registered);
    }

    // ─── Manual fire ─────────────────────────────────────────────────────

    /**
     * Trigger a registered scheduler immediately. Mirrors the cron-path
     * end-to-end (overlap policy, event-log, scheduler-log document,
     * metrics) — the only distinction is the {@code trigger=manual}
     * marker on the run's log document. Designed for the
     * {@code ursascheduler_fire} agent tool: callers receive the
     * {@code correlationId} and the path of the scheduler-log document
     * they can {@code document_read} once the run completes.
     */
    @PostMapping("/scheduler/{name}/fire")
    public FireResult fire(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        String norm = normalizeName(name);
        UrsaSchedulerService.FireOutcome outcome;
        try {
            outcome = schedulerService.fireNow(tenant, project, norm);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
        // firedAt comes back from the service so the returned logPath
        // matches the document the writer creates (no second-boundary
        // race — see ticket mhus/vance#1).
        String logPath = SchedulerLogService.pathFor(norm, outcome.firedAt(), outcome.correlationId());
        return new FireResult(outcome.correlationId(), logPath);
    }

    // ─── Events ───────────────────────────────────────────────────────────

    @GetMapping("/scheduler/{name}/events")
    public List<EventLogEntryDto> listEvents(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        String norm = normalizeName(name);
        String source = UrsaSchedulerSourceKeys.sourceFor(norm);
        List<EventLogDocument> rows = eventLogService.listBySource(tenant, source, limit);
        List<EventLogEntryDto> out = new ArrayList<>(rows.size());
        for (EventLogDocument e : rows) {
            out.add(toEventDto(e));
        }
        return out;
    }

    // ─── Mappers ──────────────────────────────────────────────────────────

    private SchedulerSummary toSummary(String tenant, String project, ResolvedUrsaScheduler r) {
        EventType[] activity = {
                EventType.STARTED, EventType.COMPLETED, EventType.FAILED, EventType.SKIPPED};
        Instant lastRun = eventLogService.findLatest(
                tenant, UrsaSchedulerSourceKeys.sourceFor(r.name()), List.of(activity))
                .map(EventLogDocument::getTimestamp)
                .orElse(null);
        return SchedulerSummary.builder()
                .name(r.name())
                .description(r.description())
                .cron(r.cron())
                .recipe(r.recipe())
                .runAs(r.effectiveRunAs())
                .enabled(r.enabled())
                .source(r.source())
                .lockMode(r.lockMode())
                .lastRunAt(lastRun)
                .nextRunAt(schedulerService.nextFireFor(tenant, project, r.name()))
                .build();
    }

    private static SchedulerDto toDto(ResolvedUrsaScheduler r) {
        return SchedulerDto.builder()
                .name(r.name())
                .yaml(r.yaml())
                .source(r.source() == null ? SchedulerSource.PROJECT : r.source())
                .description(r.description())
                .cron(r.cron())
                .at(r.at())
                .timezone(r.timezone())
                .enabled(r.enabled())
                .recipe(r.recipe())
                .params(r.params())
                .initialMessage(r.initialMessage())
                .runAs(r.runAs())
                .overlap(r.overlap() == null ? OverlapPolicy.SKIP : r.overlap())
                .lockMode(r.lockMode() == null ? LockMode.FULL : r.lockMode())
                .tags(r.tags())
                .build();
    }

    private static EventLogEntryDto toEventDto(EventLogDocument e) {
        return EventLogEntryDto.builder()
                .id(e.getId())
                .tenantId(e.getTenantId())
                .projectId(e.getProjectId())
                .source(e.getSource())
                .type(e.getType())
                .timestamp(e.getTimestamp())
                .correlationId(e.getCorrelationId())
                .sessionId(e.getSessionId())
                .processId(e.getProcessId())
                .runAs(e.getRunAs())
                .payload(e.getPayload() == null || e.getPayload().isEmpty() ? null : e.getPayload())
                .build();
    }

    private static String normalizeName(String raw) {
        String n = raw.trim().toLowerCase(Locale.ROOT);
        if (n.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scheduler name must not be blank");
        }
        return n;
    }

    /**
     * The mandatory {@link de.mhus.vance.shared.permission.WriteActor} for this
     * management surface: a trusted server write of a {@code _vance/} system
     * resource on behalf of the request user. The surface has already run its
     * own authorization (Project WRITE above); WriteReason.SYSTEM is the hint
     * that lets the resolver allow the reserved-path write while the real user
     * stays recorded for audit. (F1)
     */
    private de.mhus.vance.shared.permission.WriteActor systemActor(HttpServletRequest request) {
        return de.mhus.vance.shared.permission.WriteActor.system(authority.contextOf(request));
    }

    private static String pathFor(String name) {
        return UrsaSchedulerLoader.SCHEDULER_PATH_PREFIX + name
                + UrsaSchedulerLoader.SCHEDULER_PATH_SUFFIX;
    }

    public record RefreshResult(int registered) {
    }

    /**
     * Response of {@code POST /scheduler/{name}/fire}. {@code correlationId}
     * is the freshly-minted run identifier; {@code logPath} is the
     * project-relative document path the matching scheduler-log will
     * live at — callers can {@code document_read} it once the run
     * completes (or while it's still pending, in which case the
     * front-matter shows {@code outcome: pending}).
     */
    public record FireResult(String correlationId, String logPath) {
    }
}
