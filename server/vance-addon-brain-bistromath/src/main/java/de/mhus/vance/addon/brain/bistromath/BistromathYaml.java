package de.mhus.vance.addon.brain.bistromath;

import java.util.List;
import java.util.Map;
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
 * YAML reading for view documents and data rows.
 *
 * <p>Two decisions are baked in here, and both matter.
 *
 * <p><b>SafeConstructor.</b> A view document and a data row are untrusted
 * content — authored by a person, written by an agent, or shipped in a kit.
 * The default SnakeYAML constructor can instantiate arbitrary classes named
 * by a {@code !!} tag; the safe one cannot.
 *
 * <p><b>No timestamp resolver, and {@code true|false} only for booleans.</b>
 * A data row is displayed in a table, so scalar resolution is visible to the
 * reader. With SnakeYAML's YAML-1.1 defaults a column holding {@code no}
 * becomes the boolean false and renders as "false", and {@code 2026-01-01}
 * becomes a {@code java.util.Date} that renders as
 * "Thu Jan 01 00:00:00 CET 2026". Both are wrong in a way the author cannot
 * work around, because the cell says what they typed. The core schema keeps
 * them as strings.
 */
final class BistromathYaml {

    private BistromathYaml() {
    }

    /** Parse a document. Returns {@code null} for empty or unparseable input. */
    static @Nullable Object load(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            // SnakeYAML is not thread-safe → one instance per parse.
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()),
                    new Representer(new DumperOptions()), new DumperOptions(),
                    new CoreResolver());
            return yaml.load(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parse a document whose root must be a mapping, else an empty map. */
    static Map<?, ?> loadMap(String text) {
        return load(text) instanceof Map<?, ?> m ? m : Map.of();
    }

    /**
     * A cell value as the reader should see it.
     *
     * <p>Lists and maps are rendered compactly rather than with Java's
     * {@code toString}: a column that happens to hold a list must still read
     * as data, not as a debug dump.
     */
    static String stringify(@Nullable Object value) {
        if (value == null) return "";
        if (value instanceof String s) return s;
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(stringify(o));
            }
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(stringify(e.getKey())).append(": ").append(stringify(e.getValue()));
            }
            return sb.toString();
        }
        return String.valueOf(value);
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
