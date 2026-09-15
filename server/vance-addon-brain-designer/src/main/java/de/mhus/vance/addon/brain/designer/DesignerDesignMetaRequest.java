package de.mhus.vance.addon.brain.designer;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /addon/designer/design-meta} — edits the
 * {@code design.yaml} of one design. Blank {@code title}/{@code description}
 * remove the key; foreign keys in the file survive.
 */
@GenerateTypeScript("designer")
public record DesignerDesignMetaRequest(
        String name, @Nullable String title, @Nullable String description) {}
