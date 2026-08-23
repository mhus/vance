package de.mhus.vance.brain.megadodo;

import de.mhus.vance.api.megadodo.MegadodoEventDto;
import de.mhus.vance.api.megadodo.MegadodoPageDto;
import de.mhus.vance.api.megadodo.MegadodoRefType;
import de.mhus.vance.api.megadodo.MegadodoSeverity;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.megadodo.MegadodoEventDocument;
import de.mhus.vance.shared.megadodo.MegadodoQuery;
import de.mhus.vance.shared.megadodo.MegadodoService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read surface of the project activity feed — see
 * {@code specification/public/megadodo-system.md}.
 *
 * <p>Authorisation is {@code ADMIN}, deliberately: the feed shows what
 * ran under other people's identities and which tools failed. A project
 * admin sees their project; a tenant admin additionally sees the
 * tenant-wide rows (user created, project created), which carry no
 * {@code projectId} and therefore cannot belong to a project scope.
 */
@RestController
@RequestMapping("/brain/{tenant}/megadodo")
@RequiredArgsConstructor
public class MegadodoController {

    private final MegadodoService megadodoService;
    private final RequestAuthority authority;

    /**
     * One page of the feed, newest first.
     *
     * @param projectId the project to read; omit for the tenant-wide rows
     * @param cursor    opaque, from the previous page's {@code nextCursor}
     */
    @GetMapping
    public MegadodoPageDto feed(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam(value = "from", required = false) @Nullable String from,
            @RequestParam(value = "to", required = false) @Nullable String to,
            @RequestParam(value = "minSeverity", required = false) @Nullable String minSeverity,
            @RequestParam(value = "action", required = false) @Nullable String actionPrefix,
            @RequestParam(value = "refType", required = false) @Nullable String refType,
            @RequestParam(value = "refId", required = false) @Nullable String refId,
            @RequestParam(value = "actor", required = false) @Nullable String actor,
            @RequestParam(value = "q", required = false) @Nullable String text,
            @RequestParam(value = "cursor", required = false) @Nullable String cursor,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            HttpServletRequest request) {

        enforce(tenant, projectId, request);

        MegadodoService.MegadodoPage page = megadodoService.query(new MegadodoQuery(
                tenant,
                projectId,
                parseInstant(from),
                parseInstant(to),
                parseEnum(MegadodoSeverity.class, minSeverity),
                actionPrefix,
                parseEnum(MegadodoRefType.class, refType),
                refId,
                actor,
                text,
                cursor,
                limit));

        return MegadodoPageDto.builder()
                .items(page.items().stream().map(MegadodoController::toDto).toList())
                .nextCursor(page.nextCursor())
                .build();
    }

    /**
     * Every row of one operation, oldest first — what the UI shows when a
     * collapsed START/END line is expanded.
     */
    @GetMapping("/trace/{traceId}")
    public List<MegadodoEventDto> trace(
            @PathVariable("tenant") String tenant,
            @PathVariable("traceId") String traceId,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            HttpServletRequest request) {

        enforce(tenant, projectId, request);
        return megadodoService.byTrace(tenant, traceId).stream()
                .map(MegadodoController::toDto)
                .toList();
    }

    /**
     * Project scope when a project is named, tenant scope otherwise.
     * Tenant-wide rows have no project to check against, so reading them
     * has to be a tenant-admin decision.
     */
    private void enforce(String tenant, @Nullable String projectId, HttpServletRequest request) {
        if (projectId != null && !projectId.isBlank()) {
            authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        } else {
            authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        }
    }

    private static MegadodoEventDto toDto(MegadodoEventDocument doc) {
        return MegadodoEventDto.builder()
                .id(doc.getId())
                .timestamp(doc.getTimestamp())
                .action(doc.getAction())
                .phase(doc.getPhase())
                .severity(doc.getSeverity())
                .outcome(doc.getOutcome())
                .traceId(doc.getTraceId())
                .projectId(doc.getProjectId())
                .actor(doc.getActor())
                .refType(doc.getRefType())
                .refId(doc.getRefId())
                .message(doc.getMessage())
                .logPath(doc.getLogPath())
                .details(doc.getDetails() == null || doc.getDetails().isEmpty()
                        ? null : doc.getDetails())
                .build();
    }

    /** Unparseable input means "no bound", not a 400 — a stale bookmark still works. */
    private static @Nullable Instant parseInstant(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static <E extends Enum<E>> @Nullable E parseEnum(Class<E> type, @Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
