package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Request body for {@code POST /brain/{tenant}/addon/wiki/page}. Only
 * {@code title} is required; {@code space} defaults to the wiki root.
 */
@GenerateTypeScript("wiki")
public record WikiCreatePageRequest(
        String title,
        @Nullable String space) {}
