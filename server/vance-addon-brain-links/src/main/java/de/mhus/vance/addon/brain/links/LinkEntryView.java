package de.mhus.vance.addon.brain.links;

import de.mhus.vance.api.annotations.GenerateTypeScript;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One link for the Web-UI.
 *
 * <p>{@code teaser} and {@code image} are the <em>stored overrides</em> and
 * are usually null — the client falls back to the link-preview proxy for
 * both. That asymmetry is deliberate and belongs in the wire shape rather
 * than being hidden behind a server-side merge: a card has to be able to
 * tell "somebody wrote this" from "this is what the page says today", and
 * only the first is editable.
 *
 * <p>{@code host} is derived, sent so every surface shows the same source
 * label without re-parsing the URL.
 */
@GenerateTypeScript("links")
public record LinkEntryView(
        String url,
        @Nullable String title,
        @Nullable String teaser,
        @Nullable String image,
        @Nullable String group,
        List<String> tags,
        @Nullable String note,
        @Nullable String addedAt,
        @Nullable String viewedAt,
        String host) {

    public static LinkEntryView of(LinkEntry entry) {
        return new LinkEntryView(entry.url(), entry.title(), entry.teaser(), entry.image(),
                entry.group(), entry.tags(), entry.note(),
                entry.addedAt() == null ? null : entry.addedAt().toString(),
                entry.viewedAt() == null ? null : entry.viewedAt().toString(),
                LinkUrls.hostLabel(entry.url()));
    }
}
