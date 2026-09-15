package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /addon/designer/design} — creates a design folder.
 * {@code name} is the folder segment (single, no reserved {@code _}
 * prefix); {@code title}/{@code description} land in the design's
 * {@code design.yaml} when non-blank.
 */
@GenerateTypeScript("designer")
public record DesignerDesignCreateRequest(
        String name, @Nullable String title, @Nullable String description) {}
