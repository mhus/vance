package de.mhus.vance.brain.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the hook side of the prompt/manual convention
 * ({@code specification/prompts-and-manuals.md} §8).
 *
 * <p>A manual is only lazy-loading if something tells the model when to load
 * it. A <em>negation</em> manual — one written to stop the model saying "there
 * is none" — is worse than useless without a hook: it becomes relevant exactly
 * when the model is not looking for it, so {@code how_do_i} and
 * {@code manual_list} never fire. Three such manuals shipped hookless.
 *
 * <p>The second test is the cheap converse: a hook that names a manual which
 * does not exist costs a tool round-trip and teaches the model that
 * {@code manual_read} is unreliable.
 */
class BundledPromptManualHookTest {

    /**
     * Manuals whose whole purpose is to fire before a wrong "not available"
     * answer. Both chat engines must hook them by name.
     */
    private static final List<String> NEGATION_MANUALS =
            List.of("mounted-docs", "starred", "kit-provisioning");

    private static final List<String> CHAT_PROMPTS =
            List.of("arthur-prompt.md", "eddie-prompt.md");

    private static final Pattern HOOK =
            Pattern.compile("manual_read\\(\\s*'([^']+)'\\s*\\)");

    @Test
    void everyNegationManualIsHookedFromBothChatPrompts() {
        for (String prompt : CHAT_PROMPTS) {
            Set<String> hooked = hookedManuals(readPrompt(prompt));
            assertThat(hooked)
                    .as("%s must hook the negation manuals with the exact "
                            + "manual_read('name') call syntax", prompt)
                    .containsAll(NEGATION_MANUALS);
        }
    }

    @Test
    void everyHookedManualExists() {
        List<String> missing = new ArrayList<>();
        for (String prompt : CHAT_PROMPTS) {
            for (String name : hookedManuals(readPrompt(prompt))) {
                // `kind-<X>` hooks are templated over the kind registry, not
                // one file per name — they resolve at call time.
                if (name.startsWith("kind-")) continue;
                if (!manualExists(name)) missing.add(prompt + " → " + name);
            }
        }
        assertThat(missing)
                .as("a hook naming a manual that is not bundled costs a "
                        + "round-trip and teaches the model to distrust manual_read")
                .isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Set<String> hookedManuals(String prompt) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = HOOK.matcher(prompt);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static boolean manualExists(String name) {
        for (String dir : List.of("_vance/manuals", "_vance/eddie/manuals")) {
            if (Files.isRegularFile(resource("vance-defaults/" + dir).resolve(name + ".md"))) {
                return true;
            }
        }
        return false;
    }

    private static String readPrompt(String fileName) {
        Path path = resource("vance-defaults/_vance/prompts").resolve(fileName);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolve a bundled resource directory under {@code target/classes}.
     *
     * <p>Deliberately not {@code ClassPathResource}: {@code src/test/resources}
     * carries its own {@code vance-defaults/_vance/prompts} for engine-fragment
     * fixtures, and on the test classpath that one wins — the lookup would
     * silently land in a directory holding two fixture files instead of the
     * shipped prompts.
     */
    private static Path resource(String classpathDir) {
        Path testClasses;
        try {
            testClasses = Path.of(BundledPromptManualHookTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("cannot locate target/test-classes", e);
        }
        Path dir = testClasses.resolveSibling("classes").resolve(classpathDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("not a bundled resource directory: " + dir);
        }
        return dir;
    }
}
