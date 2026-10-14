package de.mhus.vance.shared.lease;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable view of a held lease, handed to {@link LeaseHandler#onRevoked}
 * — everything from the holder document the handler needs for cleanup.
 *
 * @param token     holder token ({@code _id} of the holder document)
 * @param type      lease type, selects the handler
 * @param resource  resource key of the lease definition (counter identity)
 * @param holderRef consumer-specific instance key
 * @param tenantId  owning tenant
 * @param projectId owning project, {@code null} for project-less holders
 * @param userId    owning user, {@code null} for user-less holders
 * @param pod       pod id of the acquiring pod
 * @param podBound  whether the resource dies with the holder pod
 * @param payload   consumer-specific cleanup data (job id, session id, …)
 */
public record LeaseHolderInfo(
        String token,
        String type,
        String resource,
        String holderRef,
        String tenantId,
        @Nullable String projectId,
        @Nullable String userId,
        String pod,
        boolean podBound,
        Map<String, Object> payload) {

    public LeaseHolderInfo {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** The handler view of a holder document. */
    public static LeaseHolderInfo of(ResourceLeaseHolderDocument holder) {
        return new LeaseHolderInfo(
                holder.getToken(),
                holder.getType(),
                holder.getResource(),
                holder.getHolderRef(),
                holder.getTenantId(),
                holder.getProjectId(),
                holder.getUserId(),
                holder.getPod(),
                holder.isPodBound(),
                holder.getPayload() == null ? Map.of() : holder.getPayload());
    }
}
