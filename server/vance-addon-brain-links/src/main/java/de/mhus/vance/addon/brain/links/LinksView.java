package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTO for a links scan — manifest header, group order, entries in
 * display order. Every mutating endpoint answers with this, so the client
 * never has to guess what a change did to the ordering.
 */
@GenerateTypeScript("links")
public record LinksView(
        String folder,
        @Nullable String title,
        @Nullable String description,
        List<String> groups,
        List<LinkEntryView> entries) {}
