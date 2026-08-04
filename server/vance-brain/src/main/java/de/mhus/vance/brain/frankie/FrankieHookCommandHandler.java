package de.mhus.vance.brain.frankie;

import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandHandler;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Frankie {@code frankie.hook} verb — read/change the post-completion
 * hook's goal template at runtime. Subcommands (in the command's
 * {@code text} argument):
 *
 * <ul>
 *   <li>{@code get} (or empty) — return the effective template
 *       (runtime override → recipe → built-in default);</li>
 *   <li>{@code set <template>} — set a per-process override;</li>
 *   <li>{@code clear} / {@code reset} — drop the override.</li>
 * </ul>
 *
 * <p>Engine-scoped to Frankie. See {@code planning/engine-commands.md} §4
 * and {@code specification/frankie-engine.md} §14.
 */
@Component
@RequiredArgsConstructor
public class FrankieHookCommandHandler implements EngineCommandHandler {

    private final ThinkProcessService thinkProcessService;
    private final FrankiePostCompletionHookHandler hookHandler;

    @Override
    public String verb() {
        return "frankie.hook";
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        if (!FrankieEngine.NAME.equalsIgnoreCase(process.getThinkEngine())) {
            return EngineCommandResult.unknown(
                    "frankie.hook is only available on the frankie engine");
        }
        String[] head = splitFirstToken(argText(command));
        String sub = head[0].isEmpty() ? "get" : head[0].toLowerCase(Locale.ROOT);
        String rest = head[1];
        return switch (sub) {
            case "get" -> get(process);
            case "set" -> set(process, rest);
            case "clear", "reset" -> clear(process);
            default -> EngineCommandResult.error(
                    "unknown subcommand '" + sub + "' (get | set <template> | clear)");
        };
    }

    private EngineCommandResult get(ThinkProcessDocument process) {
        String effective = hookHandler.resolveEffectiveGoalTemplate(process);
        String override = process.getPostCompletionHookGoalOverride();
        boolean overridden = override != null && !override.isBlank();
        String source = overridden ? "override" : "recipe/default";
        if (!hookHandler.hasPostCompletionHook(process)) {
            source += "; note: recipe declares no post-completion hook — it won't fire";
        }
        return EngineCommandResult.ok(source, effective);
    }

    private EngineCommandResult set(ThinkProcessDocument process, String template) {
        if (template.isBlank()) {
            return EngineCommandResult.error(
                    "set requires a template: //frankie.hook set <prompt>");
        }
        if (!thinkProcessService.setPostCompletionHookGoalOverride(process.getId(), template)) {
            return EngineCommandResult.error("process not found");
        }
        String msg = hookHandler.hasPostCompletionHook(process)
                ? "hook goal template overridden"
                : "hook goal template overridden — WARNING: recipe declares no "
                        + "post-completion hook, so it won't fire yet";
        return EngineCommandResult.ok(msg, null);
    }

    private EngineCommandResult clear(ThinkProcessDocument process) {
        if (!thinkProcessService.setPostCompletionHookGoalOverride(process.getId(), null)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("hook goal override cleared (back to recipe/default)", null);
    }

    private static String argText(EngineCommand command) {
        Object text = command.args().get("text");
        return text == null ? "" : text.toString().trim();
    }

    /** Splits {@code "set my template"} into {@code ["set", "my template"]}. */
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
