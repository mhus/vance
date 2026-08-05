package de.mhus.vance.brain.command;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Generic {@code llm} verb — override the LLM sampling parameters of any
 * process at runtime (engine-agnostic). Writes the live
 * {@code engineParamOverrides} overlay that {@code EngineChatFactory}
 * merges in front of the spawn-static recipe {@code engineParams} on every
 * turn, so the change takes effect on the next model call without a
 * respawn. Subcommands (in the command's {@code text} argument):
 *
 * <ul>
 *   <li>{@code <key> <value>} — set an override
 *       ({@code temperature}/{@code topP}/{@code topK}/{@code maxTokens}/
 *       {@code seed}/{@code frequencyPenalty}/{@code presencePenalty};
 *       case-insensitive);</li>
 *   <li>{@code <key> clear} — drop that override, back to the recipe
 *       default;</li>
 *   <li>{@code get} (or empty) — list the active overrides.</li>
 * </ul>
 *
 * <p>The whitelist is deliberate: this verb only exposes sampling knobs,
 * not structural params like {@code model} (runtime model-switching is a
 * separate concern — alias resolution + capability gating) or
 * {@code disableCache}. The reasoning level has its own ergonomic alias
 * {@code //thinking}, which writes the same overlay under key
 * {@code thinking}. See {@code specification/public/engine-commands.md} §9.
 */
@Component
@RequiredArgsConstructor
public class LlmCommandHandler implements EngineCommandHandler {

    private final ThinkProcessService thinkProcessService;

    /** Whitelisted sampling params: lowercase alias → canonical key + value parser. */
    private static final Map<String, Param> PARAMS = buildParams();

    private record Param(String canonical, Function<String, Object> parse) {}

    @Override
    public String verb() {
        return "llm";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String[] head = splitFirstToken(argText(command));
        String key = head[0];
        String rest = head[1];
        if (key.isEmpty() || "get".equalsIgnoreCase(key)) {
            return get(process);
        }
        if ("clear".equalsIgnoreCase(key)) {
            return EngineCommandResult.error("clear needs a param: //llm <key> clear (" + knownKeys() + ")");
        }
        Param param = PARAMS.get(key.toLowerCase(Locale.ROOT));
        if (param == null) {
            return EngineCommandResult.error("unknown llm param '" + key + "' (" + knownKeys() + " | get)");
        }
        if ("clear".equalsIgnoreCase(rest)) {
            return clear(process, param.canonical());
        }
        return set(process, param, rest);
    }

    private EngineCommandResult get(ThinkProcessDocument process) {
        Map<String, Object> overrides = process.getEngineParamOverrides();
        String active = PARAMS.values().stream()
                .map(Param::canonical)
                .distinct()
                .filter(k -> overrides != null && overrides.containsKey(k))
                .map(k -> k + "=" + overrides.get(k))
                .collect(Collectors.joining(" "));
        return active.isEmpty()
                ? EngineCommandResult.ok("no llm overrides (recipe defaults active)", null)
                : EngineCommandResult.ok("llm overrides: " + active, null);
    }

    private EngineCommandResult set(ThinkProcessDocument process, Param param, String rawValue) {
        if (rawValue.isBlank()) {
            return EngineCommandResult.error("set requires a value: //llm " + param.canonical() + " <value>");
        }
        Object value;
        try {
            value = param.parse().apply(rawValue.trim());
        } catch (NumberFormatException e) {
            return EngineCommandResult.error(
                    param.canonical() + " must be a number, got '" + rawValue.trim() + "'");
        }
        if (!thinkProcessService.setEngineParamOverride(process.getId(), param.canonical(), value)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok(
                param.canonical() + " set to " + value + " (from next turn)", null);
    }

    private EngineCommandResult clear(ThinkProcessDocument process, String canonical) {
        if (!thinkProcessService.setEngineParamOverride(process.getId(), canonical, null)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok(canonical + " override cleared — back to recipe default", null);
    }

    private static String knownKeys() {
        return PARAMS.values().stream()
                .map(Param::canonical)
                .distinct()
                .collect(Collectors.joining(" | "));
    }

    private static Map<String, Param> buildParams() {
        Map<String, Param> m = new LinkedHashMap<>();
        m.put("temperature", new Param("temperature", LlmCommandHandler::asDouble));
        m.put("topp", new Param("topP", LlmCommandHandler::asDouble));
        m.put("topk", new Param("topK", LlmCommandHandler::asInt));
        m.put("maxtokens", new Param("maxTokens", LlmCommandHandler::asInt));
        m.put("seed", new Param("seed", LlmCommandHandler::asLong));
        m.put("frequencypenalty", new Param("frequencyPenalty", LlmCommandHandler::asDouble));
        m.put("presencepenalty", new Param("presencePenalty", LlmCommandHandler::asDouble));
        return Map.copyOf(m);
    }

    private static Object asDouble(String s) {
        return Double.valueOf(s);
    }

    private static Object asInt(String s) {
        return Integer.valueOf(s);
    }

    private static Object asLong(String s) {
        return Long.valueOf(s);
    }

    private static String argText(EngineCommand command) {
        Object text = command.args().get("text");
        return text == null ? "" : text.toString().trim();
    }

    private static String[] splitFirstToken(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return new String[] {"", ""};
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.isWhitespace(t.charAt(i))) {
                return new String[] {t.substring(0, i), t.substring(i + 1).trim()};
            }
        }
        return new String[] {t, ""};
    }
}
