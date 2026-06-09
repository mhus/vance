package de.mhus.vance.brain.fook;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * Identifies who filed a ticket. Mirrors the
 * {@code reporterKind}/{@code reporterUserId}/{@code reporterTenantId}/
 * {@code reporterServiceAccount} scalars carried in {@code $meta} on
 * persisted ticket documents.
 *
 * <p>{@link Kind#ENGINE} carries a user-and-tenant pair — the
 * process's owner. {@link Kind#USER_DIRECT} is a Fook-button click;
 * also user-and-tenant. {@link Kind#SERVICE_ACCOUNT} fills
 * {@link #getServiceAccount} instead; in v1 those submissions land
 * without an inbox item (see {@code planning/fook-service.md} §7).
 */
@Value
@Builder
public class TicketReporter {

    public enum Kind {
        ENGINE,
        USER_DIRECT,
        SERVICE_ACCOUNT
    }

    Kind kind;

    /** Set for {@link Kind#ENGINE} and {@link Kind#USER_DIRECT}. */
    @Nullable String userId;

    /** Tenant where the reporter lives — NOT the {@code _vance}
     *  storage tenant. Used as the cross-tenant inbox target. */
    @Nullable String tenantId;

    /** Set for {@link Kind#SERVICE_ACCOUNT}. */
    @Nullable String serviceAccount;
}
