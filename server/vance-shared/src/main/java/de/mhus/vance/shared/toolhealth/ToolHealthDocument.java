package de.mhus.vance.shared.toolhealth;

import de.mhus.vance.api.toolhealth.ToolHealthClassification;
import de.mhus.vance.api.toolhealth.ToolHealthScope;
import de.mhus.vance.api.toolhealth.ToolHealthStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persistent health record for one tool in one scope. Look up via the
 * scope cascade in {@link ToolHealthService#lookup}; write via the
 * {@code mark*} / {@code setCooldown*} methods.
 *
 * <p>See {@code specification/tool-availability.md} §5.
 */
@Document(collection = "tool_health")
@CompoundIndexes({
        // Primary lookup key — narrowest scope first.
        @CompoundIndex(
                name = "tenant_scope_key_tool_uidx",
                def = "{ 'tenantId': 1, 'scope': 1, 'scopeId': 1, 'toolName': 1 }",
                unique = true),
        // Listing by status (admin dashboards).
        @CompoundIndex(
                name = "tenant_scope_status_idx",
                def = "{ 'tenantId': 1, 'scope': 1, 'status': 1 }"),
        // Cross-tenant tool overview.
        @CompoundIndex(
                name = "tool_status_idx",
                def = "{ 'toolName': 1, 'status': 1 }")
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolHealthDocument {

    @Id
    private @Nullable String id;

    /**
     * Tenant the entry belongs to. Always set, even for {@code GLOBAL}
     * scope — we keep cross-tenant data in the same collection but the
     * tenant boundary stays explicit. {@code GLOBAL}-scope entries can
     * be replicated per tenant or maintained centrally — for v1 we
     * always write a per-tenant copy when a {@code GLOBAL} verdict
     * reaches a tenant.
     */
    private String tenantId = "";

    private ToolHealthScope scope = ToolHealthScope.PROJECT;

    /**
     * Identifier within {@link #scope}. {@code sessionId} / {@code userId}
     * / {@code projectId} / {@code tenantId}. {@code null} only allowed
     * for {@code GLOBAL} scope, where there is no further qualifier.
     * Stored as empty string when null so the compound index remains
     * consistent — readers translate empty → null.
     */
    private String scopeId = "";

    private String toolName = "";

    // ─── Status (orthogonal to cooldowns) ─────────────────────────────

    private ToolHealthStatus status = ToolHealthStatus.OK;

    /** When the current status was first set (or last changed). */
    private @Nullable Instant since;

    /** Last successful probe or successful tool invocation that touched this entry. */
    private @Nullable Instant lastCheckedAt;

    /** Estimated recovery time, or {@code null} when unknown. */
    private @Nullable Instant expectedRecoveryAt;

    /** Free-text note attached to the latest status change. */
    private @Nullable String lastNote;

    /** Classification that produced the current status (may differ from history tail). */
    private @Nullable ToolHealthClassification lastClassification;

    // ─── Cooldowns (orthogonal to status) ─────────────────────────────

    /**
     * Active cooldown entries keyed by {@code (errorSignature, userId)}.
     * See {@link ToolHealthCooldown}.
     */
    private List<ToolHealthCooldown> cooldowns = new ArrayList<>();

    // ─── History ─────────────────────────────────────────────────────

    /**
     * Ring-buffer of past status events. Newest first. Bounded by
     * {@link ToolHealthService#HISTORY_MAX_ENTRIES}.
     */
    private List<ToolHealthHistoryEntry> history = new ArrayList<>();

    private Instant createdAt = Instant.EPOCH;
    private Instant updatedAt = Instant.EPOCH;

    // Spring Data MongoDB writes fields via reflection and can leave the
    // collection slots at null when the BSON document is missing the key
    // or stores an explicit null (legacy docs persisted before the field
    // existed). Lazy-init in the getter so callers can always iterate and
    // mutate without a defensive null-check.

    public List<ToolHealthCooldown> getCooldowns() {
        if (cooldowns == null) {
            cooldowns = new ArrayList<>();
        }
        return cooldowns;
    }

    public List<ToolHealthHistoryEntry> getHistory() {
        if (history == null) {
            history = new ArrayList<>();
        }
        return history;
    }
}
