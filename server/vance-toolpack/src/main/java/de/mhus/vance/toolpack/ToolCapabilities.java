package de.mhus.vance.toolpack;

/**
 * Names of host capabilities a {@link Tool} may require
 * ({@link Tool#requiresCapabilities()}). A host that lacks a capability leaves the
 * tool out of every inventory instead of listing it and failing on call.
 */
public final class ToolCapabilities {

    /**
     * Access to the server-side workspace: arbitrary files on the pod, programs
     * started there. Absent when the operator switches workspace access off
     * ({@code vance.workspace.mode=off}).
     */
    public static final String WORKSPACE = "workspace";

    private ToolCapabilities() {}
}
