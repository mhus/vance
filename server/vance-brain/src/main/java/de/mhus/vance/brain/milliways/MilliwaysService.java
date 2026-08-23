package de.mhus.vance.brain.milliways;

import de.mhus.vance.api.milliways.ShareFormDto;
import de.mhus.vance.api.milliways.ShareHandlerDto;
import de.mhus.vance.api.milliways.ShareResultDto;
import de.mhus.vance.brain.prompt.UntrustedContent;
import de.mhus.vance.shared.audit.AuditEventDto;
import de.mhus.vance.shared.audit.AuditService;
import de.mhus.vance.shared.audit.AuditSeverity;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.metric.MetricService;
import de.mhus.vance.shared.net.SafeLink;
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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Milliways — the one entry point for "show this to a human".
 *
 * <p>The façade does exactly three things itself: sanitise and resolve the
 * subject, enforce {@code Document READ} when the subject names a document,
 * and record the act. Which ways exist, what they need to know and how they
 * deliver belongs to the {@link ShareHandler}s — and so does any authorization
 * beyond that read check, the same split {@code RunSource} uses.
 *
 * <p><b>The façade checks only what the subject asserts.</b> A link and a
 * snippet come from the sharer, so there is nothing to authorize against them;
 * an outbound brake belongs to the handler that sends outward, not here.
 * Milliways does not know whether a way leads inside or outside the house, and
 * therefore cannot scale its checks by that.
 *
 * <p>Sharing changes no permission — it never grants access. See
 * {@code specification/public/milliways-system.md} and
 * {@code planning/milliways-subject.md}.
 */
@Service
@Slf4j
public class MilliwaysService {

    static final String METRIC_SHARES = "vance.milliways.shares";
    static final String AUDIT_ACTION = "milliways.share";

    /** A title lands in a mail Subject header and an inbox item title. */
    static final int MAX_TITLE_CHARS = 300;

    private final Map<String, ShareHandler> handlers;
    private final DocumentService documentService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MetricService metricService;
    private final int maxSnippetChars;

    public MilliwaysService(
            List<ShareHandler> handlers,
            DocumentService documentService,
            PermissionService permissionService,
            AuditService auditService,
            MetricService metricService,
            @Value("${vance.milliways.snippet.max-chars:2000}") int maxSnippetChars) {
        this.handlers = index(handlers);
        this.documentService = documentService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.metricService = metricService;
        this.maxSnippetChars = maxSnippetChars > 0 ? maxSnippetChars : 2000;
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
            audit(handlerId, target, target.subject(), "denied", AuditSeverity.WARN,
                    Map.of("reason", "read_denied"));
            throw e;
        } catch (ShareException e) {
            // Also counted and audited: a subject refused while being defanged
            // (bad link scheme) or a document that is gone is a refused share
            // attempt, not a non-event. Catching only PermissionDeniedException
            // here let those pass through invisibly — no counter, no audit
            // entry, and `denied` stopped meaning "an attempt was refused".
            count(handlerId, "denied");
            audit(handlerId, target, target.subject(), "denied", AuditSeverity.INFO,
                    Map.of("reason", String.valueOf(e.getMessage())));
            throw e;
        }
        try {
            requireAvailable(handler, scope);
        } catch (ShareUnavailableException e) {
            // Counted, not audited: nothing left the building and nobody was
            // refused on security grounds — but a spike here means clients
            // are acting on a stale handler list, which is worth seeing.
            count(handlerId, "unavailable");
            throw e;
        }

        ShareResult result;
        try {
            result = handler.share(new ShareRequest(scope, values));
        } catch (ShareUnavailableException e) {
            // The pack disappeared between availability() and share(). Same
            // classification as the check above — counted, not audited: a
            // handler that stopped being usable is not a refusal.
            count(handlerId, "unavailable");
            throw e;
        } catch (ShareException e) {
            count(handlerId, "denied");
            audit(handlerId, target, scope.subject(), "denied", AuditSeverity.INFO,
                    Map.of("reason", String.valueOf(e.getMessage())));
            throw e;
        } catch (PermissionDeniedException e) {
            // A handler enforcing its own resource (the app handler's
            // `Project WRITE` on the target app) is a refusal, not a broken
            // relay — `failed` is the alarm signal for "the way out is dead"
            // and must not be noised up by authorization.
            count(handlerId, "denied");
            audit(handlerId, target, scope.subject(), "denied", AuditSeverity.WARN,
                    Map.of("reason", "handler_denied"));
            throw e;
        } catch (RuntimeException e) {
            count(handlerId, "failed");
            audit(handlerId, target, scope.subject(), "failed", AuditSeverity.WARN,
                    Map.of("error", e.getClass().getSimpleName()));
            throw e;
        }

        count(handlerId, "success");
        audit(handlerId, target, scope.subject(), "success", AuditSeverity.INFO, result.details());
        log.info("Shared tenantId='{}' projectId='{}' subject={} via '{}' by '{}'",
                target.tenantId(), target.projectId(), scope.subject().parts(),
                handlerId, target.ctx().subjectId());
        return ShareResultDto.builder()
                .handlerId(handler.id())
                .message(result.message())
                .details(result.details())
                .build();
    }

    // ──────────────────── internals ────────────────────

    /**
     * Sanitises the subject, enforces {@code READ} on the project it happens
     * in, and — when the subject names a document — resolves that document
     * and enforces {@code READ} on it too. Also the gate for listing: which
     * ways out exist here is not something a user who cannot read the place
     * needs to learn.
     *
     * <p><b>The project is not a free selector.</b> {@code projectId} arrives
     * in the request body and decides which packs resolve, whose relay sends
     * and what the audit trail says the act belonged to. A subject without a
     * document carries nothing else to authorize against, so without this
     * check a user with no grant on {@code finance} could list its
     * {@code smtp_sender} packs — From addresses included — and then send
     * arbitrary text through its credentials. §8.1 accepts that a link plus a
     * snippet can leave through <em>the project relay</em>; it does not accept
     * picking the project.
     *
     * <p>Project {@code READ} costs the document-carrying case nothing: a
     * document read already inherits from the project.
     */
    private ShareScope resolve(ShareTarget target) {
        ShareSubject subject = sanitise(target.subject());
        permissionService.enforce(
                target.ctx(),
                new Resource.Project(target.tenantId(), target.projectId()),
                Action.READ);
        if (!subject.hasDocument()) {
            return ShareScope.of(target, subject, null);
        }
        String path = subject.documentPath();
        permissionService.enforce(
                target.ctx(),
                new Resource.Document(target.tenantId(), target.projectId(), path),
                Action.READ);
        DocumentDocument doc = documentService
                .findByPath(target.tenantId(), target.projectId(), path)
                .orElseThrow(() -> new ShareNotFoundException(
                        "Document '" + target.projectId() + "/" + path + "' not found"));
        return ShareScope.of(target, subject, doc);
    }

    /**
     * Defangs what came from outside, once, so no handler can forget it.
     *
     * <p>The link is checked against the scheme allow-list — it becomes a
     * clickable link in a mail client, where the browser's own guard never
     * runs. The snippet and the title are foreign text: whitespace collapsed
     * (the helper Zarniwoop pushes its hit rows through) and length-capped,
     * because both end up in a mail body and an inbox item and a "snippet" of
     * two megabytes is a payload, not a quote.
     */
    private ShareSubject sanitise(ShareSubject raw) {
        String link;
        try {
            link = raw.link() == null ? null : SafeLink.require(raw.link());
        } catch (SafeLink.UnsafeLinkException e) {
            throw new ShareException(e.getMessage(), e);
        }
        return new ShareSubject(
                cap(collapse(raw.title()), MAX_TITLE_CHARS),
                link,
                cap(collapse(raw.snippet()), maxSnippetChars),
                raw.document());
    }

    private static @Nullable String collapse(@Nullable String text) {
        if (text == null) return null;
        String collapsed = UntrustedContent.collapseWhitespace(text).trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** Cuts at the last word boundary and marks the cut, like the hit rows do. */
    private static @Nullable String cap(@Nullable String text, int max) {
        if (text == null || text.length() <= max) return text;
        String head = text.substring(0, max);
        int lastSpace = head.lastIndexOf(' ');
        if (lastSpace > max / 2) head = head.substring(0, lastSpace);
        return head + "…";
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

    /**
     * Records the act. {@code subject} is the <b>sanitised</b> form wherever
     * one exists — the raw one would report parts that became empty after
     * trimming, so a snippet of pure whitespace would leave a trace claiming
     * a quote nobody ever saw. Only the {@code resolve} failure path falls
     * back to the raw subject, because there sanitising is what failed.
     */
    private void audit(
            String handlerId,
            ShareTarget target,
            ShareSubject subject,
            String outcome,
            AuditSeverity severity,
            Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("handler", handlerId);
        // Which parts were shared, and the link's host. Without this a
        // document-less share leaves no trace of what went out: `target`
        // carries the path when there is one and nothing when there is not.
        // The host, not the full URL — same reason the mail handler records
        // recipient domains rather than addresses.
        merged.put("subject", subject.parts());
        String linkHost = SafeLink.hostOf(subject.link());
        if (linkHost != null) merged.put("linkHost", linkHost);
        merged.putAll(details);
        String path = subject.documentPath();
        auditService.record(AuditEventDto.builder()
                .action(AUDIT_ACTION)
                .severity(severity)
                .outcome(outcome)
                .actor(target.ctx().subjectId())
                .tenantId(target.tenantId())
                .projectId(target.projectId())
                .target(path == null
                        ? target.projectId()
                        : target.projectId() + "/" + path)
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
