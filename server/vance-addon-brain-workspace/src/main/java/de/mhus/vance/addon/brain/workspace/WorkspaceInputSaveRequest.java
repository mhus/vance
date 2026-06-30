package de.mhus.vance.addon.brain.workspace;

import org.jspecify.annotations.Nullable;

/** Request for {@code POST /brain/{tenant}/addon/workspace/input/save}: the
 *  full text content to persist into the bound document. */
public record WorkspaceInputSaveRequest(@Nullable String content) {}
