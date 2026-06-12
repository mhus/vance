package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One brain-pod row as the cluster dashboard sees it. Fed by the
 * cluster pods endpoint, which filters {@code activeProjects} down to
 * the requesting tenant before serialising — {@link #tenantProjects}
 * is therefore the project-name list visible to <em>this</em> tenant
 * only, with the {@code <tenantId>/} prefix already stripped.
 *
 * <p>{@link #stale} is observer-derived from
 * {@link #lastHeartbeatAt} on the brain that answered the request, so
 * a missed heartbeat shows up without the pod itself having to flip
 * its status. {@link #master} is observer-derived from the
 * {@code cluster_master} lease.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@GenerateTypeScript("insights")
public class BrainPodInsightsDto {

    /** Human-friendly node alias, unique within {@link #clusterId}. */
    private String nodeName;

    /** Boot-stable pod identifier (UUID). */
    private String podId;

    /** Cluster grouping ({@code vance.cluster.id}). */
    private String clusterId;

    /** Pod-self-advertised {@code host:port}. */
    private String endpoint;

    /** Pod-self-reported lifecycle phase: STARTING / RUNNING / STOPPING / STOPPED. */
    private String status;

    /** {@code true} when {@link #lastHeartbeatAt} is older than the cluster stale window. */
    private boolean stale;

    /** {@code true} for the row that represents the brain currently serving this request. */
    private boolean selfPod;

    /** {@code true} for the row that currently holds the cluster-master lease. */
    private boolean master;

    /** Set on registration; never updated. */
    private @Nullable Instant bootedAt;

    /** Refreshed on every heartbeat. */
    private @Nullable Instant lastHeartbeatAt;

    /** Optional build version. */
    private @Nullable String version;

    /**
     * Projects this pod currently owns <em>that belong to the
     * requesting tenant</em>. Other tenants' projects are filtered out
     * server-side and never appear here. Each entry carries the
     * project's lifecycle status, lifecycle type, and resource-score
     * — see {@link BrainPodProjectInsightsDto}.
     */
    @Builder.Default
    private List<BrainPodProjectInsightsDto> tenantProjects = new ArrayList<>();
}
