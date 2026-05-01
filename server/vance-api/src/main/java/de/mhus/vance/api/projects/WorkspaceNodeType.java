package de.mhus.vance.api.projects;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Distinguishes file-system entries returned by the workspace tree API. */
@GenerateTypeScript("projects")
public enum WorkspaceNodeType {
    FILE,
    DIR
}
