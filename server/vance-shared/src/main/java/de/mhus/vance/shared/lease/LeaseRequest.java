package de.mhus.vance.shared.lease;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Lease request handed to {@link ResourceLeaseService#acquire}. Built by
 * consumer code — the type and {@code podBound} are set by the consumer
 * because only it knows what it has to clean up when the lease is revoked;
 * the lease definition comes from configuration, either by name (resolved
 * through the four-layer cascade, see {@link LeaseDefinitionLoader}) or
 * completely inline (no cascade, no merge).
 */
public record LeaseRequest(
        String type,
        @Nullable String leaseName,
        @Nullable LeaseDefinition inline,
        String tenantId,
        @Nullable String projectId,
        @Nullable String userId,
        String holderRef,
        String pod,
        boolean podBound,
        @Nullable Map<String, Object> payload) {

    public LeaseRequest {
        if (leaseName == null && inline == null) {
            throw new IllegalArgumentException("LeaseRequest needs a lease name or an inline definition");
        }
        if (leaseName != null && inline != null) {
            throw new IllegalArgumentException(
                    "LeaseRequest takes either a lease name or an inline definition, not both");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder — see the record for the meaning of the fields. */
    public static final class Builder {
        private @Nullable String type;
        private @Nullable String leaseName;
        private @Nullable LeaseDefinition inline;
        private @Nullable String tenantId;
        private @Nullable String projectId;
        private @Nullable String userId;
        private @Nullable String holderRef;
        private @Nullable String pod;
        private boolean podBound;
        private @Nullable Map<String, Object> payload;

        /** Handler type ({@code "tex"}, {@code "mcp"}, …) — mandatory. */
        public Builder type(String type) {
            this.type = type;
            return this;
        }

        /** Name-referenced lease definition (config-document cascade). */
        public Builder lease(String leaseName) {
            this.leaseName = leaseName;
            return this;
        }

        /** Completely inline definition — no cascade, no merge. */
        public Builder inline(LeaseDefinition inline) {
            this.inline = inline;
            return this;
        }

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder projectId(@Nullable String projectId) {
            this.projectId = projectId;
            return this;
        }

        public Builder userId(@Nullable String userId) {
            this.userId = userId;
            return this;
        }

        /** Consumer-specific instance key (process id, connection key, …). */
        public Builder holderRef(String holderRef) {
            this.holderRef = holderRef;
            return this;
        }

        /** Pod id of the acquiring pod (ClusterService.selfPodId()). */
        public Builder pod(String pod) {
            this.pod = pod;
            return this;
        }

        /**
         * Whether the leased resource lives inside the holder pod and dies
         * with it (stdio subprocess, local process) — decides the revoke
         * behaviour on a dead pod, see {@code planning/lease-system.md} §8.
         */
        public Builder podBound(boolean podBound) {
            this.podBound = podBound;
            return this;
        }

        /** Initial payload — what the handler needs for cleanup. */
        public Builder payload(Map<String, Object> payload) {
            this.payload = payload;
            return this;
        }

        public LeaseRequest build() {
            return new LeaseRequest(
                    require(type, "type"),
                    leaseName,
                    inline,
                    require(tenantId, "tenantId"),
                    projectId,
                    userId,
                    require(holderRef, "holderRef"),
                    require(pod, "pod"),
                    podBound,
                    payload);
        }

        private static String require(@Nullable String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("LeaseRequest field '" + field + "' is required");
            }
            return value;
        }
    }
}
