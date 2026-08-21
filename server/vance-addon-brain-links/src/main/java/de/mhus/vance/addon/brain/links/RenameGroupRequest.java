package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /addon/links/group/rename}. A blank {@code to}
 * dissolves the group: its links move to the ungrouped lead group and the
 * heading disappears.
 */
@GenerateTypeScript("links")
public record RenameGroupRequest(String from, @Nullable String to) {}
