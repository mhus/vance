package de.mhus.vance.shared.permission;

import lombok.Getter;

/**
 * Thrown by {@link PermissionService#enforce} when a check fails. Inbound
 * layers translate this to {@code 403 Forbidden} (REST) or an error frame
 * (WebSocket); business code does not catch it.
 *
 * <p>Deliberately not extending Spring Security's {@code AccessDeniedException}
 * — we are not on Spring Security's filter chain and don't want its
 * machinery to be activated by accident.
 */
@Getter
public class PermissionDeniedException extends RuntimeException {

    private final SecurityContext subject;
    private final Resource resource;
    private final Action action;

    public PermissionDeniedException(SecurityContext subject, Resource resource, Action action) {
        super(buildMessage(subject, resource, action));
        this.subject = subject;
        this.resource = resource;
        this.action = action;
    }

    private static String buildMessage(SecurityContext subject, Resource resource, Action action) {
        return "subject=%s:%s tenant=%s denied %s on %s".formatted(
                subject.subjectType(), subject.subjectId(), subject.tenantId(), action, resource);
    }
}
