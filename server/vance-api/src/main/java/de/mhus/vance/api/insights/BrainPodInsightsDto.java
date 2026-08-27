package de.mhus.vance.api.insights;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
     * What this pod <em>is</em> — the filter half of a placement decision.
     * A project is eligible here when every entry of its
     * {@code placementSelector} appears in this map with the same value.
     *
     * <p>{@code null} on rows written before the field existed, which is the
     * same state as empty.
     */
    private @Nullable Map<String, String> labels;

    /**
     * {@code true} when a project <em>without</em> a selector is not eligible
     * here — the pod refusing ordinary work so it stays available for what it
     * was provisioned for. Together with empty {@link #labels} it is a full
     * cordon.
     */
    private boolean exclusive;

    /**
     * Sum of {@code homeResourceScore} over the projects this pod owns, across
     * <em>all</em> tenants. Deliberately unfiltered while
     * {@link #tenantProjects} is filtered: the number that decides placement is
     * the pod's real load, and showing a per-tenant subtotal here would explain
     * the wrong decision.
     */
    private int resourcesCurrentScore;

    /**
     * The pod's capacity cap. Present because the per-project scores in
     * {@link #tenantProjects} have no scale without it — the fit stage compares
     * against this, and a reader who cannot see it cannot tell a busy pod from
     * an idle one.
     */
    private int resourcesMaxScore;

    /**
     * Runtime correction of {@link #resourcesMaxScore}, or {@code null} when
     * none is set. Carried separately rather than folded into the value above
     * so the dashboard can say <em>that</em> the cap was overridden — it
     * disappears on the next pod re-registration, and a number that silently
     * changes back needs to have announced itself as temporary.
     */
    private @Nullable Integer resourcesMaxScoreOverride;

    /**
     * The cap placement actually compares against — {@code override ?? max},
     * clamped to at least 1. Sent computed so no client re-implements the
     * precedence rule and gets it subtly wrong.
     */
    private int effectiveMaxScore;

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
