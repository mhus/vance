package de.mhus.vance.addon.brain.gtd;

import de.mhus.vance.shared.document.DocumentDocument;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A scanned GTD action — the backing document plus the fields the bucket
 * resolver needs, read cheaply from mirrored {@code headers} + native fields.
 * The {@link GtdBucket} is <b>not</b> stored here — it is derived on demand
 * (with today's date) by {@link GtdBucketResolver}.
 *
 * @param doc          the backing {@code kind: action} document
 * @param relativePath path relative to the suite root
 * @param inInbox      the action sits under {@code inbox/} (unprocessed)
 * @param project      project name when under {@code projects/<name>/}, else null
 */
public record GtdAction(
        DocumentDocument doc,
        String relativePath,
        boolean inInbox,
        @Nullable String project,
        String title,
        String when,
        @Nullable String deadline,
        List<String> contexts,
        boolean done) {}
