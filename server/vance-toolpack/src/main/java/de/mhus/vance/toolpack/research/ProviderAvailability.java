package de.mhus.vance.toolpack.research;

/**
 * Reason an instance returns when {@code ZarniwoopService} asks whether
 * it can serve a request in the current scope. The dispatcher only
 * dispatches to instances with {@link #READY}; everything else is
 * surfaced in the {@code research_providers} tool so the operator (and
 * the LLM via discovery) can see why an instance is sitting idle.
 */
public enum ProviderAvailability {

    /** Ready to take a request. */
    READY,

    /** No credentials configured in the scope (key missing). */
    NO_CREDENTIALS,

    /** Quota observed as zero on the most recent probe. */
    QUOTA_EXHAUSTED,

    /** ToolHealthService reports an active cooldown for this instance. */
    COOLDOWN,

    /** Explicitly disabled (operator turned the instance off). */
    DISABLED
}
