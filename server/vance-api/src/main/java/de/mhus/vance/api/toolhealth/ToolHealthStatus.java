package de.mhus.vance.api.toolhealth;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Health verdict for one (scope, scopeId, toolName) tuple. Read by the
 * manifest builder to decide whether to annotate / hide a tool.
 */
@GenerateTypeScript("toolhealth")
public enum ToolHealthStatus {
    OK,
    /** Working but sporadic — intermittent errors expected. */
    DEGRADED,
    /** Not usable. {@code expectedRecoveryAt} may carry an estimate. */
    DOWN
}
