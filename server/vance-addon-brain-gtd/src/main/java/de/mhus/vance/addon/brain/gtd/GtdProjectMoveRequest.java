package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /project} — re-file an existing action into
 * {@code projects/<slug>/}. A blank or absent {@code project} files it back into
 * {@code actions/}. Relocation only: no action field changes, so the bucket is
 * untouched.
 */
@GenerateTypeScript("gtd")
public record GtdProjectMoveRequest(@Nullable String project) {}
