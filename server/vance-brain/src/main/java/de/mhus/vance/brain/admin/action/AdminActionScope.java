package de.mhus.vance.brain.admin.action;

/**
 * Where an admin action can run. The Settings page picks the scope row
 * (tenant or a project); the tab filters the action list with this and
 * the controller rejects a project run for a {@link #TENANT_ONLY}
 * action before it reaches {@link AdminAction#run}.
 */
public enum AdminActionScope {

    /** Runnable only in the tenant scope (work is pod- or tenant-global). */
    TENANT_ONLY,

    /** Runnable in the tenant scope and against a plain project. */
    TENANT_AND_PROJECT
}
