package de.mhus.vance.brain.prompt;

import io.pebbletemplates.pebble.error.PebbleException;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.extension.Test;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Bridges Pebble's {@code is}-test syntax to the Jinja2 idiom
 * LLMs typically emit:
 *
 * <pre>{@code
 * {% if model is matching("gemini-.*flash.*") %}
 * {% if provider is matching("anthropic|google") %}
 * }</pre>
 *
 * <p>Pebble already supports the operator-style {@code matches}
 * ({@code {% if model matches "gemini-.*" %}}), but training data for
 * LLMs is dominated by Jinja2's {@code is matching(...)} form — without
 * this bridge, model-generated templates would silently produce false
 * matches. Both forms are supported after registering the extension.
 *
 * <p>{@code null} input always produces {@code false} (mirrors Pebble's
 * own null-safe operator semantics). An invalid regex throws a
 * {@link PebbleException} with the bad pattern in the message — better
 * to fail loud than to silently never match.
 */
public final class JinjaCompatExtension extends AbstractExtension {

    @Override
    public Map<String, Test> getTests() {
        return Map.of("matching", new MatchingTest());
    }

    @Override
    public Map<String, Filter> getFilters() {
        return Map.of("slug", new SlugFilter());
    }

    /**
     * URL- and filesystem-safe slug filter: lowercase the input,
     * replace every non-alphanumeric ASCII run with a single
     * hyphen, strip leading/trailing hyphens. {@code null} → empty
     * string. Used in Marvin postAction path templates to derive
     * a stable filename segment from a free-text goal.
     */
    private static final class SlugFilter implements Filter {

        private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
        private static final Pattern EDGE_HYPHENS = Pattern.compile("(^-+|-+$)");

        @Override
        public List<String> getArgumentNames() {
            return List.of();
        }

        @Override
        public @Nullable Object apply(
                @Nullable Object input,
                Map<String, Object> args,
                PebbleTemplate self,
                EvaluationContext context,
                int lineNumber) {
            if (input == null) return "";
            String s = input.toString().toLowerCase(Locale.ROOT);
            s = NON_ALNUM.matcher(s).replaceAll("-");
            s = EDGE_HYPHENS.matcher(s).replaceAll("");
            return s;
        }
    }

    private static final class MatchingTest implements Test {

        @Override
        public List<String> getArgumentNames() {
            return List.of("regex");
        }

        @Override
        public boolean apply(
                @Nullable Object input,
                Map<String, Object> args,
                PebbleTemplate self,
                EvaluationContext context,
                int lineNumber) {
            if (input == null) return false;
            Object rawRegex = args.get("regex");
            if (rawRegex == null) return false;
            String regex = rawRegex.toString();
            try {
                return Pattern.matches(regex, input.toString());
            } catch (PatternSyntaxException e) {
                throw new PebbleException(
                        e,
                        "matching(): invalid regex '" + regex + "' — " + e.getDescription(),
                        lineNumber,
                        self.getName());
            }
        }
    }
}
