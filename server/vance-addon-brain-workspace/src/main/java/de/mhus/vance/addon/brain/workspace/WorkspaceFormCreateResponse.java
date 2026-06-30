package de.mhus.vance.addon.brain.workspace;

/**
 * Response for {@code POST /brain/{tenant}/addon/workspace/form/create}:
 * the path of the freshly-created edit-config document. The client turns
 * it into a {@code vance:} URI and inserts a {@code vance-form} block.
 */
public record WorkspaceFormCreateResponse(
        String configPath) {}
