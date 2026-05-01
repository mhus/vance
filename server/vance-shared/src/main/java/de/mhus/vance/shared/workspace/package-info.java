/**
 * Workspace management — per-project on-disk container for transient
 * worker files (git checkouts, temp files, build artefacts). Designed
 * for pod-mobility via suspend/recover and HDD-quota reclaim.
 *
 * <p>Results that should outlive the workspace are persisted as
 * {@link de.mhus.vance.shared.document.DocumentDocument}s, not here.
 *
 * <p>Model: one {@link de.mhus.vance.shared.workspace.Workspace} per
 * project, holding N {@link de.mhus.vance.shared.workspace.RootDirHandle}s.
 * Each RootDir is a folder with a sibling JSON
 * {@link de.mhus.vance.shared.workspace.WorkspaceDescriptor}. Type-specific
 * behaviour lives in a
 * {@link de.mhus.vance.shared.workspace.WorkspaceContentHandler}
 * implementation (initially {@link de.mhus.vance.shared.workspace.TempHandler}).
 *
 * <p>Spec: {@code specification/workspace-management.md}.
 */
@NullMarked
package de.mhus.vance.shared.workspace;

import org.jspecify.annotations.NullMarked;
