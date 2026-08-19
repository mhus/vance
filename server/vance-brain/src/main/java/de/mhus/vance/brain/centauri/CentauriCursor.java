package de.mhus.vance.brain.centauri;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The outer cursor of a mixed feed: one inner cursor per stream, plus the
 * watermark of the last delivered entry.
 *
 * <p>There is no shared cursor across sources, so the bundle is the cursor.
 * It is opaque to the client (see {@link CentauriCursorCodec}) and valid
 * <b>only within one reader's view</b> — a source may personalise which
 * entries appear, so a cursor from another reader's scroll means nothing.
 * Today that holds automatically because the cursor is client-held and a
 * client is one user session; it is written down because a later "share
 * this feed position" would break it silently.
 */
public record CentauriCursor(
        Map<String, String> perStream,
        @Nullable Instant watermark,
        Set<String> exhausted) {

    public CentauriCursor {
        perStream = perStream == null ? Map.of() : Map.copyOf(perStream);
        exhausted = exhausted == null ? Set.of() : Set.copyOf(exhausted);
    }

    public static CentauriCursor fresh() {
        return new CentauriCursor(Map.of(), null, Set.of());
    }

    public boolean isFresh() {
        return perStream.isEmpty() && watermark == null && exhausted.isEmpty();
    }

    public @Nullable String cursorFor(FeedStream stream) {
        return perStream.get(stream.key());
    }

    public boolean isExhausted(FeedStream stream) {
        return exhausted.contains(stream.key());
    }

    /** Builder-ish helper used by the merge to assemble the next cursor. */
    public static final class Builder {

        private final Map<String, String> perStream = new LinkedHashMap<>();
        private final Set<String> exhausted = new LinkedHashSet<>();
        private @Nullable Instant watermark;

        public Builder carryOver(CentauriCursor previous) {
            perStream.putAll(previous.perStream());
            exhausted.addAll(previous.exhausted());
            watermark = previous.watermark();
            return this;
        }

        public Builder advance(FeedStream stream, String cursor) {
            perStream.put(stream.key(), cursor);
            return this;
        }

        public Builder markExhausted(FeedStream stream) {
            exhausted.add(stream.key());
            return this;
        }

        public Builder watermark(@Nullable Instant value) {
            if (value != null) {
                watermark = value;
            }
            return this;
        }

        /** Drop streams that are no longer part of the feed configuration. */
        public Builder retainOnly(Set<String> streamKeys) {
            perStream.keySet().retainAll(streamKeys);
            exhausted.retainAll(streamKeys);
            return this;
        }

        public CentauriCursor build() {
            return new CentauriCursor(perStream, watermark, exhausted);
        }
    }
}
