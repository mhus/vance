package de.mhus.vance.foot.ui;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Loads two bundled classpath resources used by the {@link StatusBar}
 * busy indicator:
 *
 * <ul>
 *   <li>{@code foot/thinking-phrases.txt} — short "thinking" phrases,
 *       displayed next to the spinner.</li>
 *   <li>{@code foot/phrase-authors.txt} — pseudo-attributions appended
 *       to each phrase for comedic effect ({@code phrase — Author…}).</li>
 * </ul>
 *
 * <p>Each pick is independent of the other, so a random phrase is
 * paired with a random author every busy cycle — the deliberate mismatch
 * (movie one-liner attributed to a serious thinker) is the joke.
 *
 * <p>Comments ({@code #}) and blank lines in either resource are
 * skipped. Each remaining line is one entry, trimmed. Purely cosmetic —
 * no behavior depends on the picks.
 */
@Component
@Slf4j
public class ThinkingPhrases {

    private static final String PHRASES_PATH = "foot/thinking-phrases.txt";
    private static final String AUTHORS_PATH = "foot/phrase-authors.txt";
    private static final String FALLBACK = "thinking";

    private List<String> phrases = List.of(FALLBACK);
    private List<String> authors = List.of();

    @PostConstruct
    void load() {
        phrases = loadList(PHRASES_PATH, List.of(FALLBACK));
        authors = loadList(AUTHORS_PATH, List.of());
    }

    private List<String> loadList(String path, List<String> fallback) {
        try (InputStream in = ThinkingPhrases.class.getClassLoader()
                .getResourceAsStream(path)) {
            if (in == null) {
                log.warn("ThinkingPhrases: resource '{}' not found — using fallback ({})",
                        path, fallback.isEmpty() ? "empty" : fallback.size() + " entries");
                return fallback;
            }
            List<String> loaded = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    loaded.add(trimmed);
                }
            }
            if (loaded.isEmpty()) {
                log.warn("ThinkingPhrases: '{}' is empty — using fallback", path);
                return fallback;
            }
            log.debug("ThinkingPhrases: loaded {} entries from '{}'", loaded.size(), path);
            return List.copyOf(loaded);
        } catch (IOException e) {
            log.warn("ThinkingPhrases: failed to load '{}': {} — using fallback",
                    path, e.toString());
            return fallback;
        }
    }

    /** Returns a uniformly-random phrase. */
    public String random() {
        if (phrases.isEmpty()) return FALLBACK;
        return phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
    }

    /**
     * Returns a uniformly-random author for the pseudo-attribution.
     * {@code null} when no author list is loaded — the caller then
     * renders just the phrase, without the {@code — Author} suffix.
     */
    public @org.jspecify.annotations.Nullable String randomAuthor() {
        if (authors.isEmpty()) return null;
        return authors.get(ThreadLocalRandom.current().nextInt(authors.size()));
    }
}
