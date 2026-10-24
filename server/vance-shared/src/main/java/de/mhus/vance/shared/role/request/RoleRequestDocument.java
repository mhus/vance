package de.mhus.vance.shared.role.request;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleScopeType;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.api.roles.TenantRole;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A role change an agent has asked for but must not perform: the change is
 * written down here and stays inert until a human with authority over the
 * target scope approves it through the inbox.
 *
 * <p>Deliberately separate from the inbox item that displays it. An inbox item
 * is a communication object — it may be delegated, dismissed or archived. A
 * pending rights change is a security object with its own lifecycle and audit
 * needs, and the facts it carries ({@code scopeType}, {@code subjectId}, the
 * role, {@code operation}) are what the UI renders and the effect executes —
 * never parsed back out of the free-text {@link #reason}.
 *
 * <p>A {@code GRANT} on scope {@code TENANT} carries {@link #tenantRole}, on
 * scope {@code PROJECT} {@link #projectRole}; a {@code REVOKE} carries neither.
 */
@Document(collection = "role_requests")
@CompoundIndexes({
    // Idempotency lookup: an identical pending request is reused rather than
    // duplicated, which also stops a looping agent from flooding somebody's
    // inbox.
    @CompoundIndex(
            name = "rrq_pending_idx",
            def = "{ 'tenantId':1, 'status':1, 'scopeType':1, 'scopeId':1, 'subjectType':1, 'subjectId':1 }"),
    // Subject deletion sweeps its pending requests.
    @CompoundIndex(name = "rrq_subject_idx", def = "{ 'tenantId':1, 'subjectType':1, 'subjectId':1 }"),
    @CompoundIndex(name = "rrq_status_idx", def = "{ 'status':1, 'createdAt':1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleRequestDocument {

    @Id
    private @Nullable String id;

    /** {@code TenantDocument.name}. */
    @Builder.Default
    private String tenantId = "";

    @Builder.Default
    private RoleRequestOperation operation = RoleRequestOperation.GRANT;

    @Builder.Default
    private RoleScopeType scopeType = RoleScopeType.PROJECT;

    /** PROJECT: {@code ProjectDocument.name}. TENANT: equals {@code tenantId}. */
    @Builder.Default
    private String scopeId = "";

    @Builder.Default
    private RoleSubjectType subjectType = RoleSubjectType.USER;

    /** {@code UserDocument.name} or {@code TeamDocument.name}. */
    @Builder.Default
    private String subjectId = "";

    /** Role to grant on a {@code TENANT} scope. */
    private @Nullable TenantRole tenantRole;

    /** Role to grant on a {@code PROJECT} scope. */
    private @Nullable ProjectRole projectRole;

    /**
     * Free text supplied by whoever asked — <b>untrusted</b>. It may originate
     * from injected content, so it is displayed as a quoted claim and never
     * interpreted.
     */
    private @Nullable String reason;

    /** User in whose session the request was raised (audit). */
    @Builder.Default
    private String requestedBy = "";

    /** Process that raised it, when it came from an engine (audit). */
    private @Nullable String requestedByProcessId;

    /** The inbox item carrying this request to its decider. */
    private @Nullable String inboxItemId;

    @Builder.Default
    private RoleRequestStatus status = RoleRequestStatus.PENDING;

    /** Who decided, and when — set on every terminal transition. */
    private @Nullable String decidedBy;

    private @Nullable Instant decidedAt;

    /** Populated when {@link #status} is {@link RoleRequestStatus#FAILED}. */
    private @Nullable String failureReason;

    @CreatedDate
    private @Nullable Instant createdAt;

    /** The requested role as display text, or {@code null} for a revoke. */
    public @Nullable String roleName() {
        if (tenantRole != null) {
            return tenantRole.name();
        }
        return projectRole == null ? null : projectRole.name();
    }
}
