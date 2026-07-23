package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;

/** Wire DTO for a project (folder) and its open-action count. */
@GenerateTypeScript("gtd")
public record GtdProjectView(
        String name,
        int openCount) {}
