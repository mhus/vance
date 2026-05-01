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
 * Loads a fixed list of short "thinking" phrases from the bundled
 * {@code foot/thinking-phrases.txt} classpath resource and exposes a
 * {@link #random()} pick. Used by the {@link StatusBar} busy indicator
 * to vary what gets displayed next to the spinner — purely cosmetic,
 * no behavior depends on it.
 *
 * <p>Comments ({@code #}) and blank lines in the resource are skipped.
 * Each remaining line is a phrase, trimmed.
 */
@Component
@Slf4j
public class ThinkingPhrases {

    private static final String RESOURCE_PATH = "foot/thinking-phrases.txt";
    private static final String FALLBACK = "thinking";

    private List<String> phrases = List.of(FALLBACK);

    @PostConstruct
    void load() {
        try (InputStream in = ThinkingPhrases.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.warn("ThinkingPhrases: resource '{}' not found — using fallback",
                        RESOURCE_PATH);
                return;
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
                log.warn("ThinkingPhrases: '{}' is empty — using fallback", RESOURCE_PATH);
                return;
            }
            this.phrases = List.copyOf(loaded);
            log.debug("ThinkingPhrases: loaded {} phrases", loaded.size());
        } catch (IOException e) {
            log.warn("ThinkingPhrases: failed to load '{}': {} — using fallback",
                    RESOURCE_PATH, e.toString());
        }
    }

    /** Returns a uniformly-random phrase. */
    public String random() {
        if (phrases.isEmpty()) return FALLBACK;
        return phrases.get(ThreadLocalRandom.current().nextInt(phrases.size()));
    }
}
