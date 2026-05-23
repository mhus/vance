package de.mhus.vance.api.toolhealth;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Coarse-grained classification produced directly by the tool-dispatch
 * path. Independent of the deeper {@link ToolHealthClassification} that
 * Fook's diagnostic engine may produce later.
 */
@GenerateTypeScript("toolhealth")
public enum ToolErrorCode {
    /** Tool is not registered in any source. */
    NOT_REGISTERED,
    /** Tool is known, but its backend is unreachable (client gone, MCP closed, timeout). */
    BACKEND_UNREACHABLE,
    /** Backend responded with an error — the default tool-exception bucket. */
    BACKEND_FAILED,
    /** Tool exists but the active recipe / profile filter excludes it. */
    DISALLOWED
}
