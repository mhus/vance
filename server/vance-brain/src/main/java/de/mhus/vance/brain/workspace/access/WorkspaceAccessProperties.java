package de.mhus.vance.brain.workspace.access;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code vance.workspace.access.*} — controls the two-layer routing path used
 * by the Web-UI to reach project workspaces. See
 * {@code specification/workspace-access.md}.
 */
@Data
@ConfigurationProperties(prefix = "vance.workspace.access")
public class WorkspaceAccessProperties {

    /**
     * When {@code true}, Layer 1 calls {@code WorkspaceService} directly
     * instead of proxying to Layer 2 via HTTP. Default {@code false} so dev
     * and prod exercise the same code path. Flip to {@code true} only in
     * integration tests that don't bring up the internal endpoint.
     */
    private boolean bypassProxy = false;

    /**
     * Hard cap on file content returned through the file endpoint. Files
     * larger than this respond with {@code 413 Payload Too Large}. Default
     * 10 MiB.
     */
    private long maxFileSize = 10L * 1024 * 1024;

    /**
     * Idle TTL for the routing cache. Entries unused for this duration are
     * dropped on the next access. Failure-driven invalidation runs in
     * addition to this — see spec §4.3.
     */
    private Duration cacheTtl = Duration.ofMinutes(30);

    /**
     * Connect timeout for Layer-1 → Layer-2 HTTP calls. Loopback / intra-cluster
     * traffic should be near-instant, so this stays low to fail fast on a
     * dead owner pod.
     */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Read timeout for Layer-1 → Layer-2 HTTP calls. Larger files stream
     * within this window; bump if very large workspaces need slower reads.
     */
    private Duration readTimeout = Duration.ofSeconds(30);
}
