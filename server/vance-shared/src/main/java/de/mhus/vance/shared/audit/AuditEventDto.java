package de.mhus.vance.shared.audit;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Generic audit event carrier. Producers fill in the fields they have;
 * everything except {@link #timestamp}, {@link #action} and
 * {@link #severity} is nullable so the same DTO works for very different
 * event sources (auth, settings change, project lifecycle, tool call …).
 *
 * <p>Naming convention for {@link #action}: dotted, lowercase, verb-last —
 * e.g. {@code auth.login}, {@code settings.update}, {@code project.create},
 * {@code permission.denied}. The {@link #target} field carries the affected
 * entity reference (e.g. {@code "user:_admin"}, {@code "setting:ai.default.provider"}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEventDto {

    /** Event timestamp. Auto-set to {@code Instant.now()} on record if null. */
    @Nullable
    @Builder.Default
    private Instant timestamp = Instant.now();

    /** Dotted action identifier (e.g. {@code "auth.login"}). Required. */
    @Nullable
    private String action;

    /** Severity classification. Defaults to {@link AuditSeverity#INFO}. */
    @Nullable
    @Builder.Default
    private AuditSeverity severity = AuditSeverity.INFO;

    /** Outcome shorthand — typically {@code "success"}, {@code "failure"}, {@code "denied"}. */
    @Nullable
    private String outcome;

    /** Acting principal (username, service-account name, or {@code null} for system). */
    @Nullable
    private String actor;

    /** Tenant scope. */
    @Nullable
    private String tenantId;

    /** Project scope (nullable for tenant-level events). */
    @Nullable
    private String projectId;

    /** Session scope (nullable for non-session events). */
    @Nullable
    private String sessionId;

    /** Affected entity reference (e.g. {@code "user:_admin"}, {@code "setting:foo.bar"}). */
    @Nullable
    private String target;

    /** Human-readable summary for log consumers. */
    @Nullable
    private String message;

    /** Free-form structured payload. Use sparingly — consumers may serialize it. */
    @Nullable
    private Map<String, Object> details;
}
