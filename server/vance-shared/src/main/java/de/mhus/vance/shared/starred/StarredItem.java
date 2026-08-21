package de.mhus.vance.shared.starred;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One starred document: a pointer into any project of the same tenant, plus the
 * denormalised facts a caller needs to act on it without reading the target.
 *
 * <p><b>Two authors, split by field.</b> {@code project}, {@code path},
 * {@code kind} and {@code type} are written by the server and never edited by
 * hand — a hand-twisted {@code type} breaks a "send to" with nothing in the UI
 * saying so. {@code title} is server-written but user-overridable;
 * {@code description}, {@code highlight}, {@code enabled} and {@code hidden}
 * belong to the person. {@link #extra} carries every key the codec did not
 * recognise so a round-trip cannot drop what a future version (or a human)
 * wrote.
 *
 * <p>{@code kind} and {@code type} are deliberately two axes rather than one
 * shared field: {@code kind} is the document kind (always set, {@code text} when
 * the document carries no header), {@code type} is the {@code app:} of an
 * {@code application} manifest and {@code null} otherwise. A single field would
 * put app names and kind names in one namespace and make every caller of
 * {@code findByType} branch.
 */
public record StarredItem(
        String project,
        String path,
        String kind,
        @Nullable String type,
        @Nullable String title,
        @Nullable String description,
        boolean highlight,
        boolean enabled,
        boolean hidden,
        Map<String, Object> extra) {

    /** Identity of an entry inside one starred list. */
    public record Key(String project, String path) {}

    public StarredItem {
        extra = extra == null ? Map.of() : Map.copyOf(extra);
    }

    public Key key() {
        return new Key(project, path);
    }

    public StarredVisibility visibility() {
        return StarredVisibility.of(enabled, hidden);
    }

    public boolean matches(String project, String path) {
        return this.project.equals(project) && this.path.equals(path);
    }

    /**
     * Whether this entry carries anything a human put there. Decides what an
     * unstar does: an entry with authored content is only switched off
     * ({@code enabled: false}), never removed, so a mis-click cannot eat a
     * typed description.
     *
     * <p>{@code title} does not count — the server writes it on every star.
     */
    public boolean hasAuthoredContent() {
        return (description != null && !description.isBlank())
                || highlight
                || hidden
                || !extra.isEmpty();
    }

    public StarredItem withEnabled(boolean value) {
        return new StarredItem(project, path, kind, type, title, description,
                highlight, value, hidden, extra);
    }

    public StarredItem withHidden(boolean value) {
        return new StarredItem(project, path, kind, type, title, description,
                highlight, enabled, value, extra);
    }

    /**
     * Refresh the server-owned facts from the live document, leaving every
     * authored field untouched. Used by both the star path and
     * {@code reconcile}.
     */
    public StarredItem withResolved(String kind, @Nullable String type, @Nullable String title) {
        return new StarredItem(project, path, kind, type, title, description,
                highlight, enabled, hidden, extra);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Hand-rolled builder — the record has boolean defaults that differ from {@code false}. */
    public static final class Builder {
        private String project = "";
        private String path = "";
        private String kind = DEFAULT_KIND;
        private @Nullable String type;
        private @Nullable String title;
        private @Nullable String description;
        private boolean highlight;
        private boolean enabled = true;
        private boolean hidden;
        private Map<String, Object> extra = new LinkedHashMap<>();

        public Builder project(String v) { this.project = v; return this; }
        public Builder path(String v) { this.path = v; return this; }
        public Builder kind(String v) { this.kind = v; return this; }
        public Builder type(@Nullable String v) { this.type = v; return this; }
        public Builder title(@Nullable String v) { this.title = v; return this; }
        public Builder description(@Nullable String v) { this.description = v; return this; }
        public Builder highlight(boolean v) { this.highlight = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }
        public Builder hidden(boolean v) { this.hidden = v; return this; }
        public Builder extra(Map<String, Object> v) { this.extra = new LinkedHashMap<>(v); return this; }

        public StarredItem build() {
            return new StarredItem(project, path, kind, type, title, description,
                    highlight, enabled, hidden, extra);
        }
    }

    /**
     * Kind recorded for a document that carries no header of its own. Most
     * plain Markdown documents have {@code DocumentDocument.kind == null};
     * {@code text} is the established fallback kind ("the fallback when nothing
     * matches", {@code KindHandler#detects}), so the field stays non-null and
     * callers never have to special-case an absent kind.
     */
    public static final String DEFAULT_KIND = "text";
}
