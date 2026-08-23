package de.mhus.vance.addon.brain.mastodon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the bundled {@code feeds-mastodon} manual against drifting away from
 * the code it describes.
 *
 * <p>A manual is prose and mostly untestable, but one part of this one is not:
 * it teaches a selector grammar, and the grammar lives in
 * {@link MastodonSelector}. If someone tightens the validator, the manual keeps
 * telling agents to type something that is now refused — and a manual that is
 * wrong is worse than none, because it is quoted to users with confidence.
 *
 * <p>Checked as two lists: the concrete selectors the manual must contain and
 * the validator must accept, and the traps it must contain and the validator
 * must refuse. Deliberately not a regex over every backtick — a manual teaches
 * by counter-example, so `hashtag:#linux` appears in it precisely because it is
 * wrong, and no pattern can tell that apart from an example to copy. Bending
 * the prose so a test could parse it would be the wrong way round.
 */
class MastodonManualTest {

    private static final String RESOURCE = "vance-defaults/_vance/manuals/feeds-mastodon.md";

    /** Must be in the manual, and must work. */
    private static final List<String> TAUGHT = List.of(
            "hashtag:opensource", "public:all", "public:local", "public:remote",
            "hashtag:Grüße");

    /**
     * Must be in the manual as a trap, and must be refused.
     *
     * <p>{@code account:} is the bare prefix because that is what the manual
     * writes — naming the kind is enough for a reader, and demanding a full
     * example here would be the test dictating prose.
     */
    private static final List<String> TRAPS = List.of(
            "hashtag:#linux", "hashtag:2026", "news", "account:");

    @Test
    void manual_isBundledWithAHeaderAndTheTriggersThatFindIt() {
        String text = manual();

        assertThat(text).startsWith("---");
        // Without a trigger line the manual exists and is never loaded.
        assertThat(text).contains("triggers:").contains("mastodon");
        assertThat(text).contains("requires-tools: feed_sources");
    }

    @Test
    void manual_everySelectorItTeachesStillWorks() {
        String text = manual();

        assertThat(TAUGHT).allSatisfy(selector -> {
            // Both halves matter: the manual has to name it (otherwise an agent
            // never learns the form) and the validator has to take it.
            assertThat(text).as("manual names '%s'", selector).contains(selector);
            assertThat(MastodonSelector.complain(selector))
                    .as("manual teaches '%s' as usable", selector)
                    .isEmpty();
        });
    }

    @Test
    void manual_everyTrapItWarnsAboutIsStillATrap() {
        String text = manual();

        // A warning about a mistake the validator no longer makes is noise that
        // costs an agent a manual read; one it stopped warning about is an empty
        // stream nobody can explain.
        assertThat(TRAPS).allSatisfy(selector -> {
            assertThat(text).as("manual warns about '%s'", selector).contains(selector);
            assertThat(MastodonSelector.complain(selector))
                    .as("manual calls '%s' a refusal", selector)
                    .isPresent();
        });
    }

    private static String manual() {
        try (InputStream in = MastodonManualTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("bundled manual missing: " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }
}
