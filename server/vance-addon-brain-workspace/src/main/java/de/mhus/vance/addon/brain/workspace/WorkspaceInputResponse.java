package de.mhus.vance.addon.brain.workspace;

/** Response for {@code GET /brain/{tenant}/addon/workspace/input}: the
 *  current text content of the bound document. */
public record WorkspaceInputResponse(String content) {}
