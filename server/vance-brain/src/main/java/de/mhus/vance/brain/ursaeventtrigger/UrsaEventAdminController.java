package de.mhus.vance.brain.ursaeventtrigger;

import de.mhus.vance.api.ursaevents.EventDto;
import de.mhus.vance.api.ursaevents.EventSummary;
import de.mhus.vance.api.ursaevents.EventTriggerResponse;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.ursaevents.UrsaEventLoader;
import de.mhus.vance.shared.ursaevents.ResolvedUrsaEvent;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST surface for the Web-UI insights editor. Lives under
 * {@code /brain/{tenant}/project/{project}/events} — JWT-authenticated,
 * tenant-checked by {@link de.mhus.vance.brain.access.BrainAccessFilter}.
 *
 * <p>Separate from {@link UrsaEventController} (the public, JWT-free
 * trigger endpoint at {@code /brain/{tenant}/event/{project}/{event}}).
 * Same controller pattern as scheduler: list / detail / admin-trigger
 * with the {@link Action#READ}/{@link Action#WRITE} authority checks.
 *
 * <p>The admin trigger {@link UrsaEventService#triggerAdmin} <strong>skips
 * the bearer-token check</strong> — the caller has already proven
 * tenant-admin privilege via the JWT, so demanding the event's own
 * bearer would just force operators to copy secrets into the UI.
 */
@RestController
@RequestMapping("/brain/{tenant}/project/{project}")
@RequiredArgsConstructor
@Slf4j
public class UrsaEventAdminController {

    private final UrsaEventLoader eventLoader;
    private final UrsaEventService eventService;
    private final RequestAuthority authority;

    // ─── List ─────────────────────────────────────────────────────────────

    @GetMapping("/events")
    public List<EventSummary> listEvents(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        List<ResolvedUrsaEvent> entries = eventLoader.listAll(tenant, project);
        List<EventSummary> out = new ArrayList<>(entries.size());
        for (ResolvedUrsaEvent r : entries) {
            out.add(toSummary(r));
        }
        out.sort(Comparator.comparing(EventSummary::getName));
        return out;
    }

    // ─── Get ──────────────────────────────────────────────────────────────

    @GetMapping("/events/{name}")
    public EventDto getEvent(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.READ);
        String norm = normalizeName(name);
        ResolvedUrsaEvent r = eventLoader.load(tenant, project, norm)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event '" + norm + "' not found in project '" + project + "'"));
        return toDto(r);
    }

    // ─── Admin Trigger ────────────────────────────────────────────────────

    /**
     * Admin trigger from the Web-UI. JWT-authenticated, so we know who
     * fired it — the caller's username goes into the workflow's
     * {@code startedBy} for audit. The request body is optional; when
     * present its {@code payload} field is forwarded to the workflow
     * under {@code params.payload}, identical to the public POST path.
     *
     * <p>Skips bearer-auth and {@code methods:}-whitelist enforcement —
     * the public trigger path remains the contract for external systems.
     */
    @PostMapping("/events/{name}/trigger")
    public EventTriggerResponse triggerEvent(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @RequestBody(required = false) @Nullable AdminTriggerRequest body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, project), Action.WRITE);
        String norm = normalizeName(name);
        String triggeredBy = authority.contextOf(request).subjectId();
        Object payload = body == null ? null : body.payload();

        UrsaEventService.UrsaEventTriggerResult result = eventService.triggerAdmin(
                tenant, project, norm, payload, triggeredBy);
        return new EventTriggerResponse(norm, result.workflowName(), result.workflowRunId());
    }

    /**
     * Admin trigger body. Only {@code payload} is meaningful — it
     * mirrors the JSON body shape of the public POST endpoint.
     */
    public record AdminTriggerRequest(@Nullable Object payload) {}

    // ─── Mappers ──────────────────────────────────────────────────────────

    private static EventSummary toSummary(ResolvedUrsaEvent r) {
        List<String> methods = r.methods().isEmpty()
                ? null
                : new ArrayList<>(r.methods());
        return EventSummary.builder()
                .name(r.name())
                .description(r.description())
                .workflow(r.workflow())
                .enabled(r.enabled())
                .methods(methods)
                .authConfigured(r.requiresAuth())
                .authType(r.requiresAuth() ? "bearer" : "none")
                .source(r.source())
                .tags(r.tags().isEmpty() ? null : r.tags())
                .build();
    }

    private static EventDto toDto(ResolvedUrsaEvent r) {
        List<String> methods = r.methods().isEmpty()
                ? null
                : new ArrayList<>(r.methods());
        Map<String, Object> params = r.params().isEmpty() ? null : r.params();
        return EventDto.builder()
                .name(r.name())
                .yaml(r.yaml())
                .source(r.source())
                .description(r.description())
                .workflow(r.workflow())
                .enabled(r.enabled())
                .methods(methods)
                .authConfigured(r.requiresAuth())
                .authType(r.requiresAuth() ? "bearer" : "none")
                .params(params)
                .runAs(r.runAs())
                .tags(r.tags().isEmpty() ? null : r.tags())
                .build();
    }

    private static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Event name must not be blank");
        }
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
