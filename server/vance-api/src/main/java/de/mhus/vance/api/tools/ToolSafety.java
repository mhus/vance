package de.mhus.vance.api.tools;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Safety classification of a tool — does the call mutate state somewhere?
 * Used by diagnostic / service engines (Agrajag, future Lunkwill) that may
 * only invoke {@link #SAFE_PROBE} tools during a diagnostic turn so a
 * health-check can never accidentally change the world it's measuring.
 *
 * <p>Most existing tools are {@link #MUTATING}; the small set of pure
 * lookup tools (doc_read, list_get, web_fetch, the new tool_probe_*) are
 * {@link #SAFE_PROBE}. The default for {@link de.mhus.vance.toolpack.Tool}
 * implementations is {@link #MUTATING} so legacy tools stay restricted.
 */
@GenerateTypeScript("toolhealth")
public enum ToolSafety {
    SAFE_PROBE,
    MUTATING
}
