package de.mhus.vance.shared.web;

/**
 * Outcome of a single {@code /llms.txt} probe attempt against an
 * origin. Stored on {@link WebOriginOverviewDocument} so a cache hit
 * carries enough state to short-circuit future probes — including the
 * "not present" case, which is the common one.
 */
public enum OverviewStatus {

    /** Origin published an {@code llms.txt} and we cached its body. */
    OK,

    /**
     * Origin returned 404 / 410 / similar — confirmed absence. Cached
     * with a short TTL so we re-probe occasionally; without this the
     * majority of fetches would issue a doomed extra request every
     * time.
     */
    NOT_FOUND,

    /**
     * Probe failed for a reason that does not say anything about the
     * resource (timeout, DNS, 5xx, transport error). Cached briefly to
     * smooth transient failures, but with a much shorter TTL than
     * {@link #NOT_FOUND} so a flaky moment does not poison the cache.
     */
    ERROR
}
