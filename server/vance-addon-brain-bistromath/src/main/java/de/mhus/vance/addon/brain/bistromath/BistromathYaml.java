package de.mhus.vance.addon.brain.bistromath;

import de.mhus.vance.toolpack.ToolException;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * YAML reading for view documents.
 *
 * <p>Three decisions are baked in here, and all three matter.
 *
 * <p><b>SafeConstructor.</b> A view document and a data row are untrusted
 * content — authored by a person, written by an agent, or shipped in a kit.
 * The default SnakeYAML constructor can instantiate arbitrary classes named
 * by a {@code !!} tag; the safe one cannot.
 *
 * <p><b>No timestamp resolver, and {@code true|false} only for booleans.</b>
 * A value read here can end up in front of the reader, so scalar resolution is
 * visible. With SnakeYAML's YAML-1.1 defaults a value of {@code no} becomes the
 * boolean false and renders as "false", and {@code 2026-01-01} becomes a
 * {@code java.util.Date} that renders as "Thu Jan 01 00:00:00 CET 2026". Both
 * are wrong in a way the author cannot work around, because the document says
 * what they typed. The core schema keeps them as strings.
 *
 * <p><b>A syntax error is reported, not swallowed.</b> An earlier version
 * returned {@code null} for anything unparseable, so a view with a misplaced
 * brace was reported as "is not a YAML mapping — a view starts with
 * {@code type: page}" and sent the author to look at line 1. SnakeYAML already
 * knows the line and the column; throwing them is strictly more useful than a
 * guess, and a view document is hand-written YAML, so this is the failure that
 * happens most.
 */
final class BistromathYaml {

    private BistromathYaml() {
    }

    /**
     * Parse a document, naming it if the YAML does not hold together.
     *
     * @param docPath the document's path, for the message.
     * @return {@code null} for empty input — which is a document, just an empty
     *     one; the caller decides whether that is allowed.
     * @throws ToolException when the text is not YAML, with the parser's own
     *     line and column.
     */
    static @Nullable Object load(String text, String docPath) {
        if (text == null || text.isBlank()) return null;
        try {
            // SnakeYAML is not thread-safe → one instance per parse.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()),
                    new Representer(new DumperOptions()), new DumperOptions(),
                    new CoreResolver());
            return yaml.load(text);
        } catch (RuntimeException e) {
            throw new ToolException("View '" + docPath + "' is not valid YAML: "
                    + firstLine(e.getMessage()));
        }
    }

    /**
     * SnakeYAML's message is several lines with a caret diagram. The first line
     * carries what went wrong; the rest is a picture that loses its alignment
     * anywhere it is re-wrapped, and the position follows in the next lines.
     */
    private static String firstLine(@Nullable String message) {
        if (message == null || message.isBlank()) return "unparseable";
        return message.replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }

    /** @see BistromathYaml the class comment explains why this exists. */
    private static final class CoreResolver extends Resolver {
        private static final Pattern CORE_BOOL =
                Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");

        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, CORE_BOOL, "tTfF");
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
            // TIMESTAMP / MERGE / VALUE / YAML intentionally omitted.
        }
    }
}
