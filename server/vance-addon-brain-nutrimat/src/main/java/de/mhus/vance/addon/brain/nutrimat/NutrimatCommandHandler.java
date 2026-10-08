package de.mhus.vance.addon.brain.nutrimat;

import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandHandler;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code //nutrimat …} — control plane for every Nutrimat loop nature: a
 * status view and the runtime knobs of the loop budget.
 *
 * <pre>
 * //nutrimat                     → status (same as //nutrimat status)
 * //nutrimat status              → nature, loop type, effective budget + source,
 *                                  runtime overrides, last-turn loop statistics
 * //nutrimat set maxturns 30     → runtime override of the iteration budget
 * //nutrimat set maxturns        → clear the override (back to the recipe default)
 * //nutrimat set maxdecisions 5  → salitos: judge "continue" rounds per turn
 * //nutrimat set validation on   → toggle the data-relay validation correction
 * </pre>
 *
 * <p><b>One handler for all natures</b> — the commands are properties of the
 * lab, not of a single loop. They work in every process whose engine is
 * {@code nutrimat-*}.
 *
 * <p><b>Writes go to the runtime overlay</b>
 * ({@code engineParamOverrides}, precedence Override &gt; Recipe), via the
 * atomic {@link ThinkProcessService#setEngineParamOverride} — the recipe stays
 * the baseline, the change takes effect on the next turn, and clearing the
 * override restores the recipe value. The overlay is also what the
 * {@code //llm} command uses, so the two never fight over precedence.
 *
 * <p><b>{@link #runsOnLane()} is {@code false}</b> (the {@code //wowbagger
 * info} argument): a diagnostic that queues behind a stuck turn is useless
 * exactly when it is needed. The mutation side is safe off-lane because it is
 * a targeted atomic {@code $set} on one nested key, never a write of the
 * passed document.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NutrimatCommandHandler implements EngineCommandHandler {

    /** User-facing knob names mapped onto the engine-param keys. */
    private static final Map<String, String> KNOBS = Map.of(
            "maxturns", "maxIterations",
            "maxiterations", "maxIterations",
            "maxdecisions", "maxDecisions",
            "validation", "validation");

    /** salitos' judge-round default when neither recipe nor override sets it. */
    private static final int DEFAULT_MAX_DECISIONS = 3;

    private final ThinkProcessService thinkProcessService;
    /**
     * Lazy on purpose: the engine beans pull in {@code SkillTriggerMatcher}
     * which reaches back into the command dispatcher through the skill
     * command runner — injecting the natures eagerly would close a bean
     * cycle (engine → skills → dispatcher → this handler → engine). Same
     * pattern as {@code ThinkEngineService}'s {@code ObjectProvider<RecipeResolver>}.
     */
    private final ObjectProvider<AbstractNutrimat> natures;

    @Override
    public String verb() {
        return "nutrimat";
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String engine = process.getThinkEngine();
        if (engine == null || !engine.startsWith(AbstractNutrimat.NAME_PREFIX)) {
            return EngineCommandResult.error(
                    "//nutrimat works only in a Nutrimat process — this process runs engine '" + engine + "'");
        }
        String text = command.args().get("text") instanceof String s ? s : "";
        String[] tokens = text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
        String sub = tokens.length == 0 ? "status" : tokens[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "status", "info" -> status(process);
            case "set" -> set(process, tokens);
            default ->
                EngineCommandResult.unknown(
                        "Unknown subcommand '" + sub + "' — use //nutrimat [status] or //nutrimat set <knob> [value]");
        };
    }

    // ──────────────────── status ────────────────────

    private EngineCommandResult status(ThinkProcessDocument process) {
        String engine = process.getThinkEngine();
        String nature = engine.substring(AbstractNutrimat.NAME_PREFIX.length());
        String loopType = natures.stream()
                .filter(n -> n.name().equals(engine))
                .map(AbstractNutrimat::loopType)
                .findFirst()
                .orElse("(unknown nature)");

        // The iteration budget is nature property (redbull/clubmate); 0 means
        // the loop is uncapped — the wallclock net bounds it.
        int maxIterations = natures.stream()
                .filter(n -> n.name().equals(engine))
                .map(n -> n.iterationBudget(process))
                .findFirst()
                .orElse(0);
        String maxIterationsSource = maxIterations == 0 ? "(none)" : sourceOf(process, "maxIterations");
        int maxDecisions = effectiveInt(process, "maxDecisions", DEFAULT_MAX_DECISIONS);
        String maxDecisionsSource = sourceOf(process, "maxDecisions");
        boolean validation = effectiveBool(process, "validation", false);

        StringBuilder sb = new StringBuilder();
        sb.append(engine)
                .append(" — ")
                .append(loopType)
                .append('\n')
                .append("  process:   ")
                .append(process.getName())
                .append(" (")
                .append(process.getStatus())
                .append(process.getParentProcessId() == null ? ", primary" : ", worker")
                .append(", recipe ")
                .append(process.getRecipeName() == null ? "-" : process.getRecipeName())
                .append(")\n")
                .append("  budget:    ")
                .append(
                        maxIterations > 0
                                ? "maxIterations=" + maxIterations + " (" + maxIterationsSource + ")"
                                : "— (no iteration cap, wallclock-bounded)")
                .append('\n')
                .append("  decisions: maxDecisions=")
                .append(maxDecisions)
                .append(" (")
                .append(maxDecisionsSource)
                .append(", salitos only)\n")
                .append("  validation: ")
                .append(validation ? "on" : "off");

        Map<String, Object> overrides = process.getEngineParamOverrides();
        if (overrides != null && !overrides.isEmpty()) {
            sb.append("\n  overrides: ").append(overrides);
        }

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("engine", engine);
        value.put("nature", nature);
        value.put("loopType", loopType);
        value.put("maxIterations", maxIterations);
        value.put("maxIterationsSource", maxIterationsSource);
        value.put("maxDecisions", maxDecisions);
        value.put("validation", validation);

        Object state = process.getEngineParams() == null
                ? null
                : process.getEngineParams().get("nutrimatState");
        if (state instanceof Map<?, ?> last) {
            sb.append("\n  last turn: ").append(last);
            value.put("lastTurn", last);
        } else {
            sb.append("\n  last turn: (none yet)");
        }
        return EngineCommandResult.ok(sb.toString(), value);
    }

    // ──────────────────── set ────────────────────

    private EngineCommandResult set(ThinkProcessDocument process, String[] tokens) {
        if (tokens.length < 2) {
            return EngineCommandResult.error(
                    "Usage: //nutrimat set <knob> [value] — knobs: maxturns, maxdecisions, validation");
        }
        String knob = tokens[1].toLowerCase(Locale.ROOT);
        String key = KNOBS.get(knob);
        if (key == null) {
            return EngineCommandResult.error("Unknown knob '" + knob + "' — known: maxturns, maxdecisions, validation");
        }
        // maxturns is the budget knob — only a nature with an iteration
        // budget (redbull, clubmate) has one; an uncapped loop would silently
        // ignore the override.
        if ("maxIterations".equals(key)
                && natures.stream()
                                .filter(n -> n.name().equals(process.getThinkEngine()))
                                .map(n -> n.iterationBudget(process))
                                .findFirst()
                                .orElse(0)
                        == 0) {
            return EngineCommandResult.error(
                    "this nature has no iteration budget — maxturns applies to budget natures (redbull, clubmate)");
        }
        if (tokens.length == 2) {
            // No value: clear the runtime override, back to the recipe default.
            if (!thinkProcessService.setEngineParamOverride(process.getId(), key, null)) {
                return EngineCommandResult.error("process not found: " + process.getId());
            }
            return EngineCommandResult.ok(
                    "nutrimat: " + key + " override cleared — back to the recipe default", Map.of("key", key));
        }
        String raw = tokens[2];
        Object value;
        if ("validation".equals(key)) {
            if (!Set.of("on", "off", "true", "false").contains(raw.toLowerCase(Locale.ROOT))) {
                return EngineCommandResult.error("Value for '" + knob + "' must be on/off: " + raw);
            }
            value = Boolean.parseBoolean(raw) || "on".equalsIgnoreCase(raw);
        } else {
            int number;
            try {
                number = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return EngineCommandResult.error("Value for '" + knob + "' must be a number: " + raw);
            }
            if (number < 1) {
                return EngineCommandResult.error("Value for '" + knob + "' must be >= 1: " + raw);
            }
            value = number;
        }
        if (!thinkProcessService.setEngineParamOverride(process.getId(), key, value)) {
            return EngineCommandResult.error("process not found: " + process.getId());
        }
        log.info(
                "Nutrimat[{}] id='{}' set {} = {} (runtime override)",
                process.getThinkEngine(),
                process.getId(),
                key,
                value);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("value", value);
        return EngineCommandResult.ok(
                "nutrimat: " + key + " = " + value + " (runtime override — takes effect on the next turn)", payload);
    }

    // ──────────────────── effective values ────────────────────

    private static String sourceOf(ThinkProcessDocument process, String key) {
        Map<String, Object> overrides = process.getEngineParamOverrides();
        if (overrides != null && overrides.containsKey(key)) {
            return "runtime override";
        }
        Map<String, Object> params = process.getEngineParams();
        if (params != null && params.containsKey(key)) {
            return "recipe";
        }
        return "default";
    }

    private static int effectiveInt(ThinkProcessDocument process, String key, int fallback) {
        Object v = effective(process, key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean effectiveBool(ThinkProcessDocument process, String key, boolean fallback) {
        Object v = effective(process, key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim()) || "on".equalsIgnoreCase(s.trim());
        return fallback;
    }

    private static Object effective(ThinkProcessDocument process, String key) {
        Map<String, Object> overrides = process.getEngineParamOverrides();
        if (overrides != null && overrides.containsKey(key)) {
            return overrides.get(key);
        }
        Map<String, Object> params = process.getEngineParams();
        return params == null ? null : params.get(key);
    }
}
