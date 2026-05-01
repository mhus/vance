/**
 * Brain-side workspace tooling. The {@code workspace_*} brain tools
 * (read/write/list/delete + execute_workspace_javascript) plus the
 * {@link de.mhus.vance.brain.tools.workspace.WorkspaceDirResolver}
 * helper that bridges {@link
 * de.mhus.vance.brain.tools.ToolInvocationContext} to the {@code
 * dirName} parameter on
 * {@link de.mhus.vance.shared.workspace.WorkspaceService}.
 *
 * <p>The actual workspace orchestration (RootDirs, descriptors,
 * temp/git handlers, lifecycle) lives in {@link
 * de.mhus.vance.shared.workspace}.
 */
@NullMarked
package de.mhus.vance.brain.tools.workspace;

import org.jspecify.annotations.NullMarked;
