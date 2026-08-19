package de.mhus.vance.addon.brain.centauri;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import org.jspecify.annotations.Nullable;

/**
 * Why a stream is not represented in this page — switched off, cooling down,
 * failed, too slow, unknown.
 *
 * <p>Carried to the UI rather than swallowed: a page that silently omits a
 * source looks like a source with no news, which is a different statement.
 */
@GenerateTypeScript("centauri")
public record FeedNoteView(
        String sourceId,
        String selector,
        String kind,
        @Nullable String detail) {}
