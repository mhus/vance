package de.mhus.vance.simpleauth;

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
 * A grant change an LLM has asked for but must not perform: the mutation
 * is written down here and stays inert until a human with ADMIN on the
 * target scope approves it through the inbox.
 *
 * <p>Deliberately separate from the inbox item that displays it. An inbox
 * item is a communication object — it may be delegated, dismissed or
 * archived. A pending rights change is a security object with its own
 * lifecycle and audit needs, and the facts it carries
 * ({@code scopeType}, {@code subjectId}, {@code role}, {@code operation})
 * are what the UI renders and the effect executes — never parsed back out
 * of the free-text {@link #reason}.
 *
 * <p>See {@code planning/permission-request-inbox.md} §5.
 */
@Document(collection = "permission_requests")
@CompoundIndexes({
    // Idempotency lookup: an identical pending request is reused rather
    // than duplicated, which also stops a looping agent from flooding
    // somebody's inbox.
    @CompoundIndex(name = "prq_pending_idx",
        def = "{ 'tenantId':1, 'status':1, 'scopeType':1, 'scopeId':1, "
                + "'subjectType':1, 'subjectId':1 }"),
    // Subject deletion sweeps its pending requests.
    @CompoundIndex(name = "prq_subject_idx",
        def = "{ 'tenantId':1, 'subjectType':1, 'subjectId':1 }"),
    @CompoundIndex(name = "prq_status_idx", def = "{ 'status':1, 'createdAt':1 }")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionRequestDocument {

    @Id
    private @Nullable String id;

    /** {@code TenantDocument.name}. */
    private String tenantId = "";

    private PermissionRequestOperation operation = PermissionRequestOperation.GRANT;

    private GrantScopeType scopeType = GrantScopeType.PROJECT;

    /** PROJECT: {@code ProjectDocument.name}. TENANT: equals {@code tenantId}. */
    private String scopeId = "";

    private GrantSubjectType subjectType = GrantSubjectType.USER;

    /** {@code UserDocument.name} or {@code TeamDocument.name}. */
    private String subjectId = "";

    /** Role to grant. {@code null} for {@link PermissionRequestOperation#REVOKE}. */
    private @Nullable GrantRole role;

    /**
     * Free text supplied by whoever asked — <b>untrusted</b>. It may
     * originate from injected content, so it is displayed as a quoted
     * claim and never interpreted.
     */
    private @Nullable String reason;

    /** User in whose session the request was raised (audit). */
    private String requestedBy = "";

    /** Process that raised it, when it came from an engine (audit). */
    private @Nullable String requestedByProcessId;

    /** The inbox item carrying this request to its decider. */
    private @Nullable String inboxItemId;

    @Builder.Default
    private PermissionRequestStatus status = PermissionRequestStatus.PENDING;

    /** Who decided, and when — set on every terminal transition. */
    private @Nullable String decidedBy;
    private @Nullable Instant decidedAt;

    /** Populated when {@link #status} is {@link PermissionRequestStatus#FAILED}. */
    private @Nullable String failureReason;

    @CreatedDate
    private @Nullable Instant createdAt;
}
