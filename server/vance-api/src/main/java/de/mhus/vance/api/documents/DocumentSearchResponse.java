package de.mhus.vance.api.documents;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;

/**
 * Response body for {@code GET /brain/{tenant}/documents/search}.
 *
 * <p>{@code total} is the unlimited match count; {@code items} is capped by
 * the request's {@code size}, so a client can tell "these are all of them"
 * from "refine your search".
 */
@GenerateTypeScript("documents")
public record DocumentSearchResponse(
        List<DocumentSearchItem> items,
        long total) {}
