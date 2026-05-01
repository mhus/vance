/**
 * Two-layer routing for Web-UI access to per-project workspaces. Layer 1
 * authenticates the user and proxies the request to the owner pod; Layer 2
 * sits behind an internal-token filter and delegates straight to
 * {@link de.mhus.vance.shared.workspace.WorkspaceService}. See
 * {@code specification/workspace-access.md}.
 */
@NullMarked
package de.mhus.vance.brain.workspace.access;

import org.jspecify.annotations.NullMarked;
