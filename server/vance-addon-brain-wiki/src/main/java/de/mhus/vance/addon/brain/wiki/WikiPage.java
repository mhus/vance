package de.mhus.vance.addon.brain.wiki;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.List;

/**
 * One discovered wiki page. {@code relativePath} is the path relative to
 * the wiki root (e.g. {@code mathe/analysis.md}); {@code space} is its
 * containing folder relative to the root ({@code mathe}, empty string for
 * root-level pages); {@code slug} is the file-name stem ({@code analysis}).
 *
 * <p>{@code main} marks the curated {@code main.md} home page of a space
 * (rendered as "Home", not as a content entry). {@code links} are the
 * {@code [[…]]} occurrences found in the page body — the raw material for
 * the backlink graph.
 */
public record WikiPage(
        DocumentDocument doc,
        String relativePath,
        String space,
        String slug,
        String title,
        boolean main,
        List<WikiLink> links) {
}
