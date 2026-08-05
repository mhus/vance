package de.mhus.vance.foot.session;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * Generates random two-word session names in the style
 * {@code <adjective>-<noun>} (e.g. {@code frosty-badger}).
 * Used when no explicit {@code --name} is provided so that
 * every session entry in {@code sessions.yaml} carries a
 * human-readable label without requiring user input.
 *
 * <p>The word lists are deliberately small and curated —
 * no profanity, no trademarks, all lowercase, short enough
 * to be readable in a terminal status bar.
 */
@Component
public class RandomSessionNameGenerator {

    // 50 adjectives — short, evocative, safe.
    private static final String[] ADJECTIVES = {
        "frosty", "sunny", "calm", "brave", "swift", "clever", "happy",
        "lucky", "mellow", "cosmic", "noble", "quiet", "wild", "zesty",
        "bold", "crisp", "eager", "fuzzy", "gentle", "jolly", "keen",
        "lazy", "mighty", "nimble", "placid", "rapid", "silky", "tidy",
        "vivid", "wise", "amber", "azure", "bright", "cool", "deep",
        "fair", "glad", "humble", "ivory", "jade", "kind", "lucid",
        "warm", "novel", "odd", "proud", "rosy", "shy", "vast", "witty"
    };

    // 50 nouns — animals, nature, objects.
    private static final String[] NOUNS = {
        "badger", "falcon", "river", "meadow", "summit", "harbor",
        "willow", "cedar", "otter", "raven", "fox", "owl", "hare",
        "stork", "lynx", "puma", "bison", "heron", "ibis", "koala",
        "lemur", "moose", "newt", "panda", "robin", "seal", "tiger",
        "viper", "wolf", "yak", "zebra", "canyon", "delta", "ember",
        "forest", "glacier", "hill", "island", "junction", "kettle",
        "lake", "marsh", "needle", "ocean", "peak", "quartz", "ridge",
        "stone", "valley", "wave"
    };

    /**
     * Generates a random {@code <adjective>-<noun>} name.
     * Each call picks independently from both lists
     * (50 × 50 = 2500 combinations).
     */
    public String generate() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return ADJECTIVES[rng.nextInt(ADJECTIVES.length)]
                + "-"
                + NOUNS[rng.nextInt(NOUNS.length)];
    }
}
