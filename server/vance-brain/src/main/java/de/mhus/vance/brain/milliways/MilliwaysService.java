package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.milliways.ShareFormDto;
import de.mhus.vance.api.milliways.ShareHandlerDto;
import de.mhus.vance.api.milliways.ShareResultDto;
import de.mhus.vance.shared.audit.AuditEventDto;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.audit.AuditSeverity;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionDeniedException;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Milliways — the one entry point for "show this document to a human".
 *
 * <p>The façade does exactly three things itself: resolve the document,
 * enforce {@code Document READ} for the sharer, and record the act. Which
 * ways exist, what they need to know and how they deliver belongs to the
 * {@link ShareHandler}s — and so does any authorization beyond the read
 * check, the same split {@code RunSource} uses.
 *
 * <p>Sharing changes no permission: it is a pointer plus a reason, or a
 * copy leaving the building. It never grants access. See
 * {@code planning/milliways-sharing.md}.
 */
@Service
@Slf4j
public class MilliwaysService {

    static final String METRIC_SHARES = "vance.milliways.shares";
    static final String AUDIT_ACTION = "milliways.share";

    private final Map<String, ShareHandler> handlers;
    private final DocumentService documentService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MetricService metricService;

    public MilliwaysService(
            List<ShareHandler> handlers,
            DocumentService documentService,
            PermissionService permissionService,
            AuditService auditService,
            MetricService metricService) {
        this.handlers = index(handlers);
        this.documentService = documentService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.metricService = metricService;
        log.info("Milliways ready with {} share handler(s): {}",
                this.handlers.size(), this.handlers.keySet());
    }

    /**
     * Every known handler with its current state. Order is alphabetical by
     * id — not availability-first, and not bean-discovery order: the menu
     * must not reshuffle when a pack gets configured or an addon changes
     * its load position.
     */
    public List<ShareHandlerDto> listHandlers(ShareTarget target) {
        ShareScope scope = resolve(target);
        List<ShareHandlerDto> out = new ArrayList<>(handlers.size());
        for (ShareHandler handler : handlers.values()) {
            ShareAvailability availability = availabilityOf(handler, scope);
            out.add(ShareHandlerDto.builder()
                    .id(handler.id())
                    .label(handler.label())
                    .available(availability.available())
                    .statusText(availability.statusText())
                    .build());
        }
        return out;
    }

    /**
     * The form of one available handler. Kept as a separate call from
     * {@link #listHandlers} because building it costs (a user list, a pack
     * list) and merely opening the menu should not hand out a user
     * directory.
     */
    public ShareFormDto form(String handlerId, ShareTarget target) {
        ShareHandler handler = handler(handlerId);
        ShareScope scope = resolve(target);
        requireAvailable(handler, scope);
        return ShareFormDto.builder()
                .handlerId(handler.id())
                .fields(handler.form(scope))
                .build();
    }

    /**
     * Perform the share. Records {@code outcome=denied} for a refused
     * submission and {@code outcome=failed} for a transport error, then
     * rethrows either way — the caller decides how to show it.
     */
    public ShareResultDto share(
            String handlerId, ShareTarget target, Map<String, Object> values) {
        ShareHandler handler = handler(handlerId);
        ShareScope scope;
        try {
            scope = resolve(target);
        } catch (PermissionDeniedException e) {
            count(handlerId, "denied");
            audit(handlerId, target, "denied", AuditSeverity.WARN,
                    Map.of("reason", "document_read_denied"));
            throw e;
        }
        requireAvailable(handler, scope);

        ShareResult result;
        try {
            result = handler.share(new ShareRequest(scope, values));
        } catch (ShareException e) {
            count(handlerId, "denied");
            audit(handlerId, target, "denied", AuditSeverity.INFO,
                    Map.of("reason", String.valueOf(e.getMessage())));
            throw e;
        } catch (RuntimeException e) {
            count(handlerId, "failed");
            audit(handlerId, target, "failed", AuditSeverity.WARN,
                    Map.of("error", e.getClass().getSimpleName()));
            throw e;
        }

        count(handlerId, "success");
        audit(handlerId, target, "success", AuditSeverity.INFO, result.details());
        log.info("Shared tenantId='{}' projectId='{}' path='{}' via '{}' by '{}'",
                target.tenantId(), target.projectId(), target.path(),
                handlerId, target.ctx().subjectId());
        return ShareResultDto.builder()
                .handlerId(handler.id())
                .message(result.message())
                .details(result.details())
                .build();
    }

    // ──────────────────── internals ────────────────────

    /**
     * Resolves the document and enforces {@code READ} on it. Also the gate
     * for listing: which ways out exist for a document is not something a
     * user who cannot read it needs to learn.
     */
    private ShareScope resolve(ShareTarget target) {
        permissionService.enforce(
                target.ctx(),
                new Resource.Document(target.tenantId(), target.projectId(), target.path()),
                Action.READ);
        DocumentDocument doc = documentService
                .findByPath(target.tenantId(), target.projectId(), target.path())
                .orElseThrow(() -> new ShareNotFoundException(
                        "Document '" + target.projectId() + "/" + target.path() + "' not found"));
        return ShareScope.of(target, doc);
    }

    private ShareHandler handler(String handlerId) {
        ShareHandler handler = handlers.get(handlerId);
        if (handler == null) {
            throw new ShareNotFoundException("Unknown share handler '" + handlerId + "'");
        }
        return handler;
    }

    private void requireAvailable(ShareHandler handler, ShareScope scope) {
        ShareAvailability availability = availabilityOf(handler, scope);
        if (!availability.available()) {
            throw new ShareUnavailableException(availability.statusText() == null
                    ? "Share handler '" + handler.id() + "' is not available here"
                    : availability.statusText());
        }
    }

    /**
     * A handler that throws while reporting availability is treated as
     * unavailable rather than taken down with the whole menu — one broken
     * pack must not hide the other ways out.
     */
    private ShareAvailability availabilityOf(ShareHandler handler, ShareScope scope) {
        try {
            return handler.availability(scope);
        } catch (RuntimeException e) {
            log.warn("Share handler '{}' failed to report availability: {}",
                    handler.id(), e.toString());
            metricService.exception(getClass(), "availability:" + handler.id(), e);
            return ShareAvailability.unavailable("Not usable right now — see the server log");
        }
    }

    private void count(String handlerId, String outcome) {
        metricService.counter(METRIC_SHARES, "handler", handlerId, "outcome", outcome).increment();
    }

    private void audit(
            String handlerId,
            ShareTarget target,
            String outcome,
            AuditSeverity severity,
            Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("handler", handlerId);
        merged.putAll(details);
        auditService.record(AuditEventDto.builder()
                .action(AUDIT_ACTION)
                .severity(severity)
                .outcome(outcome)
                .actor(target.ctx().subjectId())
                .tenantId(target.tenantId())
                .projectId(target.projectId())
                .target(target.projectId() + "/" + target.path())
                .details(merged)
                .build());
    }

    /**
     * Two handlers claiming the same id would make the REST path ambiguous
     * and silently shadow one of them, so it fails the boot instead.
     *
     * <p>The result iterates alphabetically by id — {@code Map.copyOf}
     * would drop the order entirely, which the listing relies on.
     */
    private static Map<String, ShareHandler> index(List<ShareHandler> handlers) {
        Map<String, ShareHandler> sorted = new TreeMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (ShareHandler handler : handlers) {
            if (sorted.putIfAbsent(handler.id(), handler) != null) {
                duplicates.add(handler.id());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalStateException(
                    "Duplicate ShareHandler id(s): " + duplicates
                            + " — a handler id must be unique across brain and addons");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
}
