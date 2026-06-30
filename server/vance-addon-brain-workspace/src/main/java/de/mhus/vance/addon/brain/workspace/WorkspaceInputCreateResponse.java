package de.mhus.vance.addon.brain.workspace;

/** Response for {@code POST /brain/{tenant}/addon/workspace/input/create}:
 *  the path of the freshly-created text document. */
public record WorkspaceInputCreateResponse(String path) {}
