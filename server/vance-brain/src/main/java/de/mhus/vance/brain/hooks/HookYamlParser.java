package de.mhus.vance.brain.hooks;

import de.mhus.vance.api.action.TriggerAction;
import de.mhus.vance.api.hooks.HookEventName;
import de.mhus.vance.api.hooks.HookSource;
import de.mhus.vance.shared.action.ActionValidationError;
import de.mhus.vance.shared.action.TriggerActionParser;
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
 * <p>A hook YAML is now a regular {@link TriggerAction} document — one
 * of {@code recipe:} / {@code script:} / {@code workflow:} at the top
 * level — wrapped with the hook-meta-fields ({@code enabled},
 * {@code description}, {@code timeout}, {@code tags}). The action
 * disjunction is enforced by {@link TriggerActionParser}; this parser
 * adds the hook-specific bits and rejects the obsolete pre-unification
 * shape ({@code type: js|llm}) with a clear migration hint.
 *
 * <p>See {@code specification/hooks.md} and
 * {@code specification/trigger-actions.md}.
 */
@Component
public class HookYamlParser {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final TriggerActionParser actionParser;

    public HookYamlParser() {
        this(new TriggerActionParser());
    }

    /** Test seam — lets unit tests inject a parser instance directly. */
    public HookYamlParser(TriggerActionParser actionParser) {
        this.actionParser = actionParser;
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

        // ── Pre-unification migration guard ─────────────────────────────
        if (spec.containsKey("type")) {
            throw new HookParseException(
                    "Hook schema changed: 'type: js|llm' is no longer supported. "
                            + "Migrate to a TriggerAction — set one of 'recipe:', "
                            + "'script:' (with source/path), or 'workflow:'. "
                            + "JS-hook scripts move to a script document referenced via "
                            + "'script: { source: document, path: ... }'. LLM-hooks "
                            + "become a script that calls 'vance.lightllm.call(...)'. "
                            + "See specification/hooks.md and specification/trigger-actions.md.");
        }
        if (spec.containsKey("prompt") || spec.containsKey("model") || spec.containsKey("maxTokens")) {
            throw new HookParseException(
                    "Hook schema changed: 'prompt' / 'model' / 'maxTokens' are no longer "
                            + "supported. LLM hooks become a script that calls "
                            + "vance.lightllm.call(...) — see specification/hooks.md.");
        }

        boolean enabled = !(spec.get("enabled") instanceof Boolean b) || b;
        String description = stringOrNull(spec.get("description"));
        Duration timeout = parseTimeout(spec.get("timeout"), DEFAULT_TIMEOUT);
        List<String> tags = stringList(spec.get("tags"));

        // ── Action variant via the shared parser ────────────────────────
        TriggerAction action;
        try {
            action = actionParser.parse(spec);
        } catch (RuntimeException ex) {
            throw new HookParseException(
                    "hook action invalid: " + ex.getMessage(), ex);
        }
        List<ActionValidationError> errors = actionParser.validate(action);
        if (!errors.isEmpty()) {
            StringBuilder msg = new StringBuilder("hook action validation failed:");
            for (ActionValidationError e : errors) {
                msg.append(" [").append(e.kind()).append(' ').append(e.field())
                        .append(": ").append(e.detail()).append(']');
            }
            throw new HookParseException(msg.toString());
        }

        return new HookDef(
                hookName, event, source, enabled, description,
                timeout, tags, yamlBody, createdByUserId, action);
    }

    // ───────────────────────── Field parsers ─────────────────────────

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
