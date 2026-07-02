package de.mhus.vance.addon.brain.workspace;

/** Response for {@code GET /brain/{tenant}/addon/workspace/input}: the
 *  editable text body of the bound document (front-matter header stripped). */
public record WorkspaceInputResponse(String content) {}
