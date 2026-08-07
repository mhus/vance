package de.mhus.vance.brain.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Binds the trailing text of a skill invocation
 * ({@code /skill <name> <rest…>}) into the {@code args} variable of the
 * Pebble render context.
 *
 * <p>Binding is <b>positional</b> — the shell convention every user
 * already knows, and the same choice the {@code //}-command surface made
 * for its raw {@code text} argument (see
 * {@code specification/public/engine-commands.md} §42). Structured
 * {@code k=v} parsing is deliberately out of scope for v1.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code args.text} — the raw trailing text, trimmed.</li>
 *   <li>{@code args.words} — the whitespace-split token list.</li>
 *   <li>Declared {@link ResolvedSkill.Argument}s bind in order, one
 *       token each; the <b>last</b> declared argument of type
 *       {@code string} binds the remaining tokens greedily.</li>
 *   <li>A missing token for a {@code required} argument raises
 *       {@link SkillArgumentException} — activation fails before the
 *       skill takes effect, rather than rendering a silently empty
 *       prompt.</li>
 *   <li>A missing token for an optional argument stays unset; the
 *       renderer runs in lenient mode, so {@code {{ args.foo }}}
 *       renders empty.</li>
 *   <li>Surplus tokens are not an error — they remain reachable via
 *       {@code args.text} / {@code args.words}.</li>
 * </ul>
 *
 * <p>Values land in the render context as <b>data</b>, never spliced
 * into the template source — a skill body is an untrusted document, and
 * concatenating user text into it would hand an author's template the
 * caller's syntax (and route around
 * {@code PromptTemplateRenderer}'s deny-all method-access validator).
 */
public final class SkillArgumentBinder {

    private SkillArgumentBinder() {}

    /**
     * Builds the {@code args} map for {@code skill} from {@code rawArgs}.
     * Returns an empty map when the skill declares no argument
     * consumption ({@code consumesArgs == false}) — an undeclared skill
     * never sees {@code args}, because the activation path injects the
     * trailing text as a plain user message instead.
     *
     * @throws SkillArgumentException when a required argument has no token
     */
    public static Map<String, Object> bind(ResolvedSkill skill, @Nullable String rawArgs) {
        if (!skill.consumesArgs()) {
            return Map.of();
        }
        String text = rawArgs == null ? "" : rawArgs.strip();
        List<String> words = text.isEmpty()
                ? List.of()
                : List.of(text.split("\\s+"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("text", text);
        args.put("words", words);

        List<ResolvedSkill.Argument> declared = skill.arguments();
        for (int i = 0; i < declared.size(); i++) {
            ResolvedSkill.Argument spec = declared.get(i);
            boolean last = i == declared.size() - 1;
            boolean greedy = last && isStringType(spec.type());
            String token = tokenFor(words, i, greedy);
            if (token == null) {
                if (spec.required()) {
                    throw new SkillArgumentException(skill.name(), spec.name());
                }
                continue;
            }
            args.put(spec.name(), coerce(skill.name(), spec, token));
        }
        return args;
    }

    /**
     * The token(s) bound to position {@code index}: one token normally,
     * everything from {@code index} on when {@code greedy}. {@code null}
     * when the invocation ran out of tokens.
     */
    private static @Nullable String tokenFor(List<String> words, int index, boolean greedy) {
        if (index >= words.size()) {
            return null;
        }
        if (!greedy) {
            return words.get(index);
        }
        return String.join(" ", words.subList(index, words.size()));
    }

    private static boolean isStringType(String type) {
        return type == null || type.isBlank() || "string".equals(type);
    }

    /**
     * Coerces a bound token to the declared type. A token that does not
     * parse is a caller error, not an author error — it fails the
     * activation with a message naming both the argument and the value.
     */
    private static Object coerce(
            String skillName, ResolvedSkill.Argument spec, String token) {
        String type = spec.type() == null || spec.type().isBlank() ? "string" : spec.type();
        try {
            return switch (type) {
                case "integer" -> Long.valueOf(token);
                case "number" -> Double.valueOf(token);
                case "boolean" -> parseBoolean(token);
                // string / object / array — objects and arrays have no
                // command-line syntax in v1, so they arrive as raw text
                // and the template does what it wants with them.
                default -> token;
            };
        } catch (NumberFormatException e) {
            throw new SkillArgumentException(skillName, spec.name(), type, token);
        }
    }

    private static Boolean parseBoolean(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return switch (t) {
            case "true", "yes", "on", "1" -> Boolean.TRUE;
            case "false", "no", "off", "0" -> Boolean.FALSE;
            default -> throw new NumberFormatException(token);
        };
    }

    /**
     * Splits {@code raw} into tokens the same way {@link #bind} does —
     * exposed so callers that only need the token view (e.g. logging,
     * client-side echo) don't re-implement the split.
     */
    public static List<String> tokenize(@Nullable String raw) {
        if (raw == null) return List.of();
        String text = raw.strip();
        if (text.isEmpty()) return List.of();
        return new ArrayList<>(List.of(text.split("\\s+")));
    }
}
