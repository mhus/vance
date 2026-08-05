package de.mhus.vance.brain.command;

import de.mhus.vance.brain.ai.EngineChatFactory;
import de.mhus.vance.brain.ai.ThinkingLevel;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Generic {@code thinking} verb — set the reasoning intensity of any
 * process at runtime (engine-agnostic). Subcommands (in the command's
 * {@code text} argument):
 *
 * <ul>
 *   <li>{@code <level>} — set the override to one of
 *       {@code off}/{@code minimal}/{@code low}/{@code medium}/{@code high};</li>
 *   <li>{@code get} (or empty) — show the effective level + override state;</li>
 *   <li>{@code clear} — drop the override, falling back to the recipe
 *       {@code params.thinking} default.</li>
 * </ul>
 *
 * <p>Ergonomic alias for {@code //llm thinking <level>} — writes the same
 * {@code engineParamOverrides.thinking} overlay ({@link LlmCommandHandler}),
 * but validates against the {@link ThinkingLevel} vocabulary. The override
 * wins over the recipe on every subsequent turn — the level is re-read
 * fresh per turn in
 * {@link EngineChatFactory#readThinkingLevel(ThinkProcessDocument)} — so
 * {@code //thinking high} takes effect on the next model call without a
 * respawn. Provider-side capability gating still applies: a model without
 * a thinking capability silently downgrades to {@code off}. See
 * {@code specification/public/engine-commands.md} §9.
 */
@Component
@RequiredArgsConstructor
public class ThinkingCommandHandler implements EngineCommandHandler {

    private final ThinkProcessService thinkProcessService;

    @Override
    public String verb() {
        return "thinking";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String arg = argText(command);
        if (arg.isEmpty() || "get".equalsIgnoreCase(arg)) {
            return get(process);
        }
        if ("clear".equalsIgnoreCase(arg)) {
            return clear(process);
        }
        return set(process, arg);
    }

    private EngineCommandResult get(ThinkProcessDocument process) {
        ThinkingLevel effective = EngineChatFactory.readThinkingLevel(process);
        Map<String, Object> overrides = process.getEngineParamOverrides();
        boolean overridden = overrides != null && overrides.containsKey("thinking");
        String state = "thinking: " + effective.name().toLowerCase(Locale.ROOT)
                + (overridden ? " (runtime override)" : " (recipe default)");
        return EngineCommandResult.ok(state, null);
    }

    private EngineCommandResult set(ThinkProcessDocument process, String arg) {
        ThinkingLevel level = ThinkingLevel.fromString(arg).orElse(null);
        if (level == null) {
            return EngineCommandResult.error(
                    "unknown thinking level '" + arg + "' (off | minimal | low | medium | high | get | clear)");
        }
        String value = level.name().toLowerCase(Locale.ROOT);
        if (!thinkProcessService.setEngineParamOverride(process.getId(), "thinking", value)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("thinking set to " + value + " (from next turn)", null);
    }

    private EngineCommandResult clear(ThinkProcessDocument process) {
        if (!thinkProcessService.setEngineParamOverride(process.getId(), "thinking", null)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("thinking override cleared — back to recipe default", null);
    }

    private static String argText(EngineCommand command) {
        Object text = command.args().get("text");
        return text == null ? "" : text.toString().trim();
    }
}
