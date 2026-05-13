package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.api.hooks.HookSource;
import de.mhus.vance.api.hooks.HookType;
import de.mhus.vance.brain.prompt.PromptTemplateRenderer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Parse and validate a hook YAML body. Single entry point —
 * {@link #parse(String, HookEventName, HookSource, String)}.
 *
 * <p>Validation is strict on shape but lenient on whitespace: trailing
 * blanks in YAML are kept verbatim so a round-trip through the editor
 * preserves the user's formatting (the parser doesn't rewrite the
 * source).
 *
 * <p>Pebble templates inside {@code prompt} are compiled here so a
 * syntax error fails the load, not the run. The compiled handle is
 * discarded; the runner re-compiles via Pebble's internal LRU cache
 * (same source string).
 */
@Component
public class HookYamlParser {

    private static final Duration DEFAULT_JS_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_LLM_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_TOKENS = 512;

    private final PromptTemplateRenderer templateRenderer;

    public HookYamlParser(PromptTemplateRenderer templateRenderer) {
        this.templateRenderer = templateRenderer;
    }

    /**
     * @param yamlBody    raw YAML text (verbatim, including comments)
     * @param event       event the hook is attached to — comes from the
     *                    document path, not the YAML body
     * @param source      cascade tier the document was found in
     * @param hookName    derived from the document filename
     */
    public HookDef parse(
            String yamlBody, HookEventName event, HookSource source, String hookName) {
        return parse(yamlBody, event, source, hookName, null);
    }

    /** Variant that carries the {@code createdBy} of the underlying document. */
    public HookDef parse(
            String yamlBody, HookEventName event, HookSource source,
            String hookName, @Nullable String createdByUserId) {
        if (yamlBody == null || yamlBody.isBlank()) {
            throw new HookParseException("hook YAML is empty");
        }

        Object parsed;
        try {
            parsed = new Yaml().load(yamlBody);
        } catch (RuntimeException ex) {
            throw new HookParseException(
                    "hook YAML invalid: " + ex.getMessage(), ex);
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new HookParseException("hook YAML must have a top-level map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> spec = (Map<String, Object>) rawMap;

        HookType type = parseType(spec.get("type"));
        boolean enabled = !(spec.get("enabled") instanceof Boolean b) || b;
        String description = stringOrNull(spec.get("description"));
        Duration timeout = parseTimeout(spec.get("timeout"),
                type == HookType.LLM ? DEFAULT_LLM_TIMEOUT : DEFAULT_JS_TIMEOUT);
        List<String> tags = stringList(spec.get("tags"));

        String script = null;
        String prompt = null;
        String model = null;
        Integer maxTokens = null;

        if (type == HookType.JS) {
            script = stringOrThrow(spec.get("script"), "script");
            if (spec.containsKey("prompt") || spec.containsKey("model")) {
                throw new HookParseException(
                        "JS hook must not declare 'prompt' or 'model' — set type: llm");
            }
        } else {
            // LLM
            prompt = stringOrThrow(spec.get("prompt"), "prompt");
            model = stringOrThrow(spec.get("model"), "model");
            maxTokens = parseMaxTokens(spec.get("maxTokens"));
            if (spec.containsKey("script")) {
                throw new HookParseException(
                        "LLM hook must not declare 'script' — set type: js");
            }
            // Fail-fast: compile the template at load time.
            try {
                templateRenderer.render(prompt, Map.of());
            } catch (RuntimeException ex) {
                throw new HookParseException(
                        "prompt Pebble template failed to compile: " + ex.getMessage(), ex);
            }
        }

        return new HookDef(
                hookName, event, source, type, enabled, description,
                timeout, tags, yamlBody, createdByUserId,
                script, model, maxTokens, prompt);
    }

    // ───────────────────────── Field parsers ─────────────────────────

    private static HookType parseType(@Nullable Object raw) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new HookParseException("'type' is required (one of: js, llm)");
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "js" -> HookType.JS;
            case "llm" -> HookType.LLM;
            default -> throw new HookParseException(
                    "unknown 'type' '" + s + "' — expected js | llm");
        };
    }

    private static Duration parseTimeout(@Nullable Object raw, Duration fallback) {
        if (raw == null) return fallback;
        Duration d;
        if (raw instanceof Number n) {
            d = Duration.ofSeconds(n.longValue());
        } else if (raw instanceof String s) {
            d = parseDurationString(s);
        } else {
            throw new HookParseException(
                    "'timeout' must be a number (seconds) or a duration string like '5s'");
        }
        if (d.isZero() || d.isNegative()) {
            throw new HookParseException("'timeout' must be positive");
        }
        if (d.compareTo(MAX_TIMEOUT) > 0) {
            throw new HookParseException(
                    "'timeout' exceeds the per-hook ceiling (" + MAX_TIMEOUT.getSeconds()
                            + "s) — split the work or shrink the wait");
        }
        return d;
    }

    private static Duration parseDurationString(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            throw new HookParseException("'timeout' is empty");
        }
        try {
            // Accept ISO-8601 ("PT5S") natively.
            if (s.startsWith("p")) {
                return Duration.parse(s.toUpperCase(Locale.ROOT));
            }
            if (s.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2).trim()));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1).trim()));
            }
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException | java.time.format.DateTimeParseException ex) {
            throw new HookParseException(
                    "'timeout' value '" + raw + "' is not a duration — use '5s', '15s', '500ms', etc.",
                    ex);
        }
    }

    private static Integer parseMaxTokens(@Nullable Object raw) {
        if (raw == null) return DEFAULT_MAX_TOKENS;
        if (!(raw instanceof Number n)) {
            throw new HookParseException("'maxTokens' must be a positive integer");
        }
        int v = n.intValue();
        if (v <= 0) {
            throw new HookParseException("'maxTokens' must be positive");
        }
        if (v > 8192) {
            throw new HookParseException(
                    "'maxTokens' is suspiciously large (" + v
                            + ") — hooks are short classifiers; lower the cap");
        }
        return v;
    }

    private static String stringOrThrow(@Nullable Object raw, String fieldName) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new HookParseException("'" + fieldName + "' is required");
        }
        return s;
    }

    private static @Nullable String stringOrNull(@Nullable Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s) || s.isBlank()) return null;
        return s;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> stringList(@Nullable Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof List<?> list)) {
            throw new HookParseException("'tags' must be a list of strings");
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof String s)) {
                throw new HookParseException("'tags' entries must be strings");
            }
            out.add(s);
        }
        return out;
    }
}
