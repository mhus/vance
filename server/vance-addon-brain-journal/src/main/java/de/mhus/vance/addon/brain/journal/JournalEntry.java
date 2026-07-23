package de.mhus.vance.addon.brain.journal;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A scanned journal entry — the backing document plus the front-matter
 * fields the board/calendar needs, read cheaply from the document's
 * mirrored {@code headers} + native fields (no blob load during a scan).
 *
 * @param doc   the backing {@code kind: journal-entry} document
 * @param date  ISO date ({@code yyyy-MM-dd}); derived from the {@code date}
 *              header, falling back to the filename stem
 * @param title display title ({@code doc.title}, else the humanised date)
 * @param mood  optional mood token
 * @param tags  native document tags
 */
public record JournalEntry(
        DocumentDocument doc,
        String date,
        String title,
        @Nullable String mood,
        List<String> tags) {}
