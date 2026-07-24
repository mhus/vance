package de.mhus.vance.addon.brain.issues;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A scanned issue — the backing document plus the fields the list/board needs,
 * read cheaply from mirrored {@code headers} + native fields.
 *
 * @param archived the file lives under {@code archive/} (out of the active tracker)
 */
public record Issue(
        DocumentDocument doc,
        int number,
        String title,
        String state,
        List<String> labels,
        @Nullable String assignee,
        @Nullable String priority,
        boolean archived) {

    public boolean isOpen() {
        return IssueDocument.STATE_OPEN.equalsIgnoreCase(state);
    }
}
