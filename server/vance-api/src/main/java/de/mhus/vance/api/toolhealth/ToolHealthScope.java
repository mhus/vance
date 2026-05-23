package de.mhus.vance.api.toolhealth;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/**
 * Reach of a tool-health entry. Lookup cascade picks the narrowest
 * available entry first — see {@code specification/tool-availability.md} §4.
 */
@GenerateTypeScript("toolhealth")
public enum ToolHealthScope {
    /** A specific bound session — typical for {@code client_*} tools. */
    SESSION,
    /** A specific user — typical when user-credentials/tokens fail. */
    USER,
    /** A specific project — typical for MCP servers, project-workspace tools. */
    PROJECT,
    /** Tenant-wide. */
    TENANT,
    /** All tenants — typical for public-API outages. */
    GLOBAL
}
