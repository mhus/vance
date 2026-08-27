package de.mhus.vance.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No property name written in the Java tree may contain an upper-case letter —
 * neither in a {@code ${...}} placeholder nor in a
 * {@code @ConditionalOnProperty} name.
 *
 * <h2>Why this is correctness and not style</h2>
 *
 * <p>A placeholder is resolved by {@code Environment.resolvePlaceholders},
 * which is <em>not</em> the binder. Boot's relaxed lookup goes through
 * {@code ConfigurationPropertyName.of}, and a name containing an upper-case
 * letter is not a valid configuration property name — so the relaxed source
 * declines and resolution falls back to literal matching against the raw
 * sources. Measured, not assumed:
 *
 * <table border="1">
 *   <caption>What each spelling can see</caption>
 *   <tr><th>Placeholder</th><th>camelCase YAML</th><th>dashed YAML</th>
 *       <th>{@code VANCE_X_FOOBAR}</th><th>{@code VANCE_X_FOO_BAR}</th></tr>
 *   <tr><td>{@code ${vance.x.fooBar}}</td><td>yes</td><td>no</td>
 *       <td>yes</td><td><b>no</b></td></tr>
 *   <tr><td>{@code ${vance.x.foo-bar}}</td><td>yes</td><td>yes</td>
 *       <td>yes</td><td>yes</td></tr>
 * </table>
 *
 * <p>The dashed spelling is therefore a strict superset: nothing that worked
 * before stops working, and the two channels an operator actually reaches for
 * — a dashed YAML key and a word-separated environment variable — start
 * working. The failure it prevents is the quiet kind: a set property with no
 * error and no effect. It cost a debugging session on the placement
 * accelerator, where a tick kept firing at its default interval while the
 * command line said otherwise
 * ({@code planning/project-placement-labels.md} §4f).
 *
 * <h2>Why the source tree and not the classpath</h2>
 *
 * <p>A reflection scan only sees what this module's classpath carries, which
 * excludes the addons — they depend on the brain, not the other way round.
 * Reading the sources covers every module and needs no class loading.
 */
class ConfigPlaceholderNamingTest {

    /** Only our own namespaces — a third-party default is not ours to spell. */
    private static final Pattern OURS = Pattern.compile(
            "\\$\\{((?:vance|spring|server|management)\\.[A-Za-z0-9_.\\-]+)");

    /**
     * {@code @ConditionalOnProperty} names, which are not placeholders but go
     * through the same {@code Environment.getProperty} and therefore share the
     * defect. Found the hard way: {@code DelegationDeadlockWatchdog} carried a
     * camel-cased key with {@code matchIfMissing = true}, so the watchdog was
     * on by default and could not be switched off from a deployment — the
     * worse half of the same bug, because "cannot be turned off" beats
     * "interval stays at its default".
     *
     * <p>Matched over a window after the annotation rather than by parsing:
     * the attributes span several lines, and the only thing that matters here
     * is whether a property name in that region carries an upper-case letter.
     */
    private static final Pattern CONDITIONAL_BLOCK =
            Pattern.compile("@ConditionalOnProperty\\s*\\(", Pattern.DOTALL);

    private static final Pattern CONDITIONAL_KEY =
            Pattern.compile("\"((?:vance|spring|server|management)\\.[A-Za-z0-9_.\\-]+)\"");

    /** How far past the annotation to look for its property names. */
    private static final int CONDITIONAL_WINDOW = 400;

    /**
     * A scan that finds nothing would report perfect compliance. The floor is
     * deliberately far below the real count (~1500 files): it guards against a
     * broken path or a moved module, not against someone deleting a class.
     */
    private static final int MINIMUM_FILES_SCANNED = 300;

    /**
     * Likewise for the placeholders themselves — if the regex stopped
     * matching, every file would look clean.
     */
    private static final int MINIMUM_PLACEHOLDERS_FOUND = 50;

    @Test
    void noPropertyNameInTheTreeCarriesAnUpperCaseLetter() {
        Scan scan = scanTree();

        assertThat(scan.filesScanned)
                .as("the scan has to actually reach the source tree — "
                        + "an empty scan is not compliance")
                .isGreaterThanOrEqualTo(MINIMUM_FILES_SCANNED);
        assertThat(scan.placeholdersFound)
                .as("the placeholder pattern has to actually match something")
                .isGreaterThanOrEqualTo(MINIMUM_PLACEHOLDERS_FOUND);

        assertThat(scan.offenders)
                .as("an upper-case letter makes the key invisible to dashed YAML and to "
                        + "word-separated environment variables — spell it lower-case "
                        + "with dashes (the value in application.yml may stay camelCase, "
                        + "a dashed placeholder reads it either way)")
                .isEmpty();
    }

    private record Scan(int filesScanned, int placeholdersFound, List<String> offenders) {}

    private static Scan scanTree() {
        Path root = moduleRoot().getParent();
        List<String> offenders = new ArrayList<>();
        int files = 0;
        int placeholders = 0;
        for (Path module : sortedChildren(root)) {
            Path sources = module.resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(sources)) {
                for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                    files++;
                    String text = read(file);
                    String where = module.getFileName() + "/" + file.getFileName();
                    Matcher matcher = OURS.matcher(text);
                    while (matcher.find()) {
                        placeholders++;
                        String key = matcher.group(1);
                        if (!key.equals(key.toLowerCase())) {
                            offenders.add(where + ": ${" + key + "}");
                        }
                    }
                    Matcher conditional = CONDITIONAL_BLOCK.matcher(text);
                    while (conditional.find()) {
                        int end = Math.min(text.length(), conditional.end() + CONDITIONAL_WINDOW);
                        Matcher key = CONDITIONAL_KEY.matcher(text.substring(conditional.end(), end));
                        while (key.find()) {
                            placeholders++;
                            String name = key.group(1);
                            if (!name.equals(name.toLowerCase())) {
                                offenders.add(where + ": @ConditionalOnProperty " + name);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new Scan(files, placeholders, offenders);
    }

    /**
     * Surefire runs with the module directory as the working directory, so the
     * reactor root is its parent. Asserted rather than assumed — a silently
     * wrong root would turn this test into decoration.
     */
    private static Path moduleRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        assertThat(cwd.resolve("src/main/java"))
                .as("expected to run from the module directory")
                .exists();
        return cwd;
    }

    private static List<Path> sortedChildren(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A source file we cannot read is a broken scan, not a pass.
            throw new UncheckedIOException(e);
        }
    }
}
