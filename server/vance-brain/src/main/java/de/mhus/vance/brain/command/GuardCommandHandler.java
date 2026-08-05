package de.mhus.vance.brain.command;

import de.mhus.vance.brain.guard.CompletionGuardService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Generic {@code guard} verb — install/inspect a completion guard on any
 * process at runtime (engine-agnostic; not scoped to one engine).
 * Subcommands (in the command's {@code text} argument):
 *
 * <ul>
 *   <li>{@code script <path>} — set the runtime guard's script path;</li>
 *   <li>{@code get} (or empty) — show the runtime override + effective guards;</li>
 *   <li>{@code clear} — drop the runtime override.</li>
 * </ul>
 *
 * <p>The runtime guard is a single guard-script path, additive to any
 * recipe {@code guard:} entries. See {@code planning/completion-guard.md} v2.9.
 */
@Component
@RequiredArgsConstructor
public class GuardCommandHandler implements EngineCommandHandler {

    private final ThinkProcessService thinkProcessService;
    private final CompletionGuardService guardService;

    @Override
    public String verb() {
        return "guard";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String[] head = splitFirstToken(argText(command));
        String sub = head[0].isEmpty() ? "get" : head[0].toLowerCase(Locale.ROOT);
        String rest = head[1];
        return switch (sub) {
            case "get" -> get(process);
            case "script" -> setScript(process, rest);
            case "clear" -> clear(process);
            default -> EngineCommandResult.error(
                    "unknown subcommand '" + sub + "' (script <path> | get | clear)");
        };
    }

    private EngineCommandResult get(ThinkProcessDocument process) {
        String script = process.getGuardScriptOverride();
        boolean runtimeActive = notBlank(script);
        int recipeGuards = (int) guardService.resolveGuards(process).stream()
                .filter(g -> !isRuntime(g, script))
                .count();
        String state = "runtime: " + (runtimeActive ? "script=" + script : "—")
                + "; recipe guards: " + recipeGuards;
        return EngineCommandResult.ok(state, null);
    }

    private EngineCommandResult setScript(ThinkProcessDocument process, String value) {
        if (value.isBlank()) {
            return EngineCommandResult.error("set requires a path: //guard script <path>");
        }
        if (!thinkProcessService.setGuardScriptOverride(process.getId(), value)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("guard script set — runtime guard now active", null);
    }

    private EngineCommandResult clear(ThinkProcessDocument process) {
        if (!thinkProcessService.setGuardScriptOverride(process.getId(), null)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("runtime guard cleared", null);
    }

    private static boolean isRuntime(de.mhus.vance.brain.recipe.GuardConfig g, @Nullable String script) {
        return script != null && script.equals(g.scriptPath());
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.isBlank();
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
