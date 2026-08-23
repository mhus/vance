package de.mhus.vance.brain.ursahooks;

import de.mhus.vance.api.megadodo.MegadodoEventDto;
import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.ursahooks.*;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.megadodo.MegadodoMapper;
import de.mhus.vance.shared.megadodo.MegadodoEventDocument;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * REST surface for the Web-UI hook editor. CRUD lives at
 * {@code /brain/{tenant}/project/{project}/hooks/{event}/{name}};
 * project-wide refresh and per-hook event-log pagination round out
 * the set.
 *
 * <p>Tenant is checked against the JWT {@code tid} claim by the
 * upstream filter before requests reach the controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/project/{project}")
@RequiredArgsConstructor
@Slf4j
public class UrsaHookController {

    private final UrsaHookService ursaHookService;
    private final UrsaHookYamlParser parser;
    private final MegadodoService megadodoService;
    private final RequestAuthority authority;

    // ─── List ──────────────────────────────────────────────────────────

    @GetMapping("/hooks")
    public List<UrsaHookSummary> listAll(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        List<UrsaHookDef> defs = ursaHookService.listAll(tenant, project);
        List<UrsaHookSummary> out = new ArrayList<>(defs.size());
        for (UrsaHookDef def : defs) {
            out.add(toSummary(tenant, project, def));
        }
        out.sort(Comparator
                .comparing(UrsaHookSummary::getEvent)
                .thenComparing(UrsaHookSummary::getName));
        return out;
    }

    @GetMapping("/hooks/{event}")
    public List<UrsaHookSummary> listForEvent(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String eventName,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        UrsaHookEventName event = parseEvent(eventName);
        List<UrsaHookDef> defs = ursaHookService.listForEvent(tenant, project, event);
        List<UrsaHookSummary> out = new ArrayList<>(defs.size());
        for (UrsaHookDef def : defs) {
            out.add(toSummary(tenant, project, def));
        }
        out.sort(Comparator.comparing(UrsaHookSummary::getName));
        return out;
    }

    // ─── Get ───────────────────────────────────────────────────────────

    @GetMapping("/hooks/{event}/{name}")
    public UrsaHookDto getOne(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String eventName,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        UrsaHookEventName event = parseEvent(eventName);
        String norm = normalizeName(name);
        UrsaHookDef def = ursaHookService.findOne(tenant, project, event, norm)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Hook '" + event.wireName() + "/" + norm + "' not found"));
        return toDto(def);
    }

    // ─── Save (create/update) ──────────────────────────────────────────

    @PutMapping("/hooks/{event}/{name}")
    public ResponseEntity<UrsaHookDto> save(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String eventName,
            @PathVariable("name") String name,
            @Valid @RequestBody UrsaHookSaveRequest body,
            HttpServletRequest request) {
        // _vance/hooks/ is server-owned (SYSTEM-only at the chokepoint); this
        // authoring surface owns the policy → require project-ADMIN, write SYSTEM.
        authority.enforce(request, new Resource.Project(tenant, project), Action.ADMIN);
        UrsaHookEventName event = parseEvent(eventName);
        String norm = normalizeName(name);
        if (body.getYaml() == null || body.getYaml().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'yaml' must be a non-empty string");
        }
        // Validate before write so the call is rejected without
        // touching the document store.
        try {
            parser.parse(body.getYaml(), event, UrsaHookSource.PROJECT, norm);
        } catch (UrsaHookParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        boolean created = ursaHookService.findOne(tenant, project, event, norm).isEmpty();
        de.mhus.vance.shared.permission.SecurityContext context = authority.contextOf(request);
        UrsaHookDef saved;
        try {
            saved = ursaHookService.save(
                    tenant, project, event, norm, body.getYaml(),
                    context.subjectId(),
                    de.mhus.vance.shared.permission.WriteActor.system(context));
        } catch (UrsaHookParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return ResponseEntity
                .status(created ? HttpStatus.CREATED : HttpStatus.OK)
                .body(toDto(saved));
    }

    // ─── Delete ────────────────────────────────────────────────────────

    @DeleteMapping("/hooks/{event}/{name}")
    public ResponseEntity<Void> delete(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String eventName,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        // _vance/hooks/ is server-owned (SYSTEM-only at the chokepoint); this
        // authoring surface owns the policy → require project-ADMIN, write SYSTEM.
        authority.enforce(request, new Resource.Project(tenant, project), Action.ADMIN);
        UrsaHookEventName event = parseEvent(eventName);
        String norm = normalizeName(name);
        boolean removed = ursaHookService.delete(tenant, project, event, norm,
                de.mhus.vance.shared.permission.WriteActor.system(authority.contextOf(request)));
        return removed
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    // ─── Refresh ───────────────────────────────────────────────────────

    @PostMapping("/hooks/refresh")
    public RefreshResult refresh(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        int registered = ursaHookService.refresh(tenant, project);
        return new RefreshResult(registered);
    }

    // ─── Events ────────────────────────────────────────────────────────

    @GetMapping("/hooks/{event}/{name}/events")
    public List<MegadodoEventDto> listEvents(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("event") String eventName,
            @PathVariable("name") String name,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        parseEvent(eventName);
        String norm = normalizeName(name);
        return megadodoService
                .listForRef(tenant, project, MegadodoRefType.HOOK, norm, limit)
                .stream()
                .map(MegadodoMapper::toDto)
                .toList();
    }

    // ─── Mappers ───────────────────────────────────────────────────────

    private UrsaHookSummary toSummary(String tenantId, String projectId, UrsaHookDef def) {
        Optional<MegadodoEventDocument> last = megadodoService.latestForRef(
                tenantId, projectId, MegadodoRefType.HOOK, def.name());
        return UrsaHookSummary.builder()
                .name(def.name())
                .event(def.event().wireName())
                .source(def.source())
                .actionType(def.actionType())
                .enabled(def.enabled())
                .description(def.description())
                .tags(def.tags())
                .lastRunAt(last.map(MegadodoEventDocument::getTimestamp).orElse(null))
                // Outcome of the newest row. Null while a run is still open —
                // a START carries no outcome yet, and that is the honest answer.
                .lastRunType(last.map(MegadodoEventDocument::getOutcome).orElse(null))
                .build();
    }

    private static UrsaHookDto toDto(UrsaHookDef def) {
        UrsaHookDto.UrsaHookDtoBuilder b = UrsaHookDto.builder()
                .name(def.name())
                .event(def.event().wireName())
                .yaml(def.yamlBody())
                .source(def.source())
                .enabled(def.enabled())
                .description(def.description())
                .timeoutMs(def.timeout().toMillis())
                .tags(def.tags())
                .params(def.action().params())
                .runAs(def.action().runAs());
        de.mhus.vance.api.action.TriggerAction action = def.action();
        if (action instanceof de.mhus.vance.api.action.TriggerAction.Recipe r) {
            b.recipe(r.recipe());
            b.initialMessage(r.initialMessage());
        } else if (action instanceof de.mhus.vance.api.action.TriggerAction.Workflow w) {
            b.workflow(w.workflow());
        } else if (action instanceof de.mhus.vance.api.action.TriggerAction.Script s) {
            b.script(UrsaHookScriptSpec.builder()
                    .source(s.source().name().toLowerCase(java.util.Locale.ROOT))
                    .dirName(s.dirName())
                    .path(s.path())
                    .timeoutSeconds(s.timeoutSeconds())
                    .build());
        }
        return b.build();
    }

    private static UrsaHookEventName parseEvent(String raw) {
        if (raw == null || !UrsaHookEventName.isKnown(raw)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown hook event '" + raw + "'");
        }
        return UrsaHookEventName.ofWire(raw);
    }

    private static String normalizeName(String raw) {
        String n = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (n.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "hook name must not be blank");
        }
        return n;
    }

    /** Response shape of {@code POST .../hooks/refresh}. */
    public record RefreshResult(int registered) {}
}
