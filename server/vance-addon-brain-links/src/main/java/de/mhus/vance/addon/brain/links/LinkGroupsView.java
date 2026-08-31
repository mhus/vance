package de.mhus.vance.addon.brain.links;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Answer of {@code GET /addon/links/groups} — the group headings, without the
 * links.
 *
 * <p>What a capture tool needs to offer a "file it under…" dropdown. Getting it
 * from {@code /scan} means downloading every entry, every teaser and every
 * picture URL to populate a select with four strings.
 *
 * <p>The lead (ungrouped) section is <b>not</b> in the list — it has no name and
 * always exists. A caller offers it as an empty choice, the same way the app
 * renders it as the absence of a heading.
 */
public record LinkGroupsView(
        String folder,
        @Nullable String title,
        List<String> groups) {}
