package de.mhus.vance.brain.command;

import de.mhus.vance.brain.guard.CompletionGuardService;
import de.mhus.vance.brain.recipe.GuardConfig;
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
 *   <li>{@code judge <text>} — set the runtime guard's judge query;</li>
 *   <li>{@code prompt <text>} — set the runtime guard's follow-up prompt;</li>
 *   <li>{@code get} (or empty) — show the runtime override + effective guards;</li>
 *   <li>{@code clear} — drop the runtime override.</li>
 * </ul>
 *
 * <p>The runtime guard becomes active only when <b>both</b> judge and
 * prompt are set, and is additive to any recipe {@code guard:} entries.
 * See {@code planning/completion-guard.md} §3.
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
            case "judge" -> setField(process, "guardJudgeOverride", rest, "judge");
            case "prompt" -> setField(process, "guardPromptOverride", rest, "prompt");
            case "clear" -> clear(process);
            default -> EngineCommandResult.error(
                    "unknown subcommand '" + sub + "' (judge <text> | prompt <text> | get | clear)");
        };
    }

    private EngineCommandResult get(ThinkProcessDocument process) {
        String judge = process.getGuardJudgeOverride();
        String prompt = process.getGuardPromptOverride();
        boolean runtimeActive = notBlank(judge) && notBlank(prompt);
        int recipeGuards = (int) guardService.resolveGuards(process).stream()
                .filter(g -> !isRuntime(g, judge, prompt))
                .count();
        String state = "runtime: judge=" + (notBlank(judge) ? "set" : "—")
                + " prompt=" + (notBlank(prompt) ? "set" : "—")
                + (runtimeActive ? " (active)" : " (incomplete)")
                + "; recipe guards: " + recipeGuards;
        return EngineCommandResult.ok(state, runtimeActive
                ? "judge: " + judge + "\nprompt: " + prompt
                : null);
    }

    private EngineCommandResult setField(
            ThinkProcessDocument process, String field, String value, String label) {
        if (value.isBlank()) {
            return EngineCommandResult.error(
                    "set requires text: //guard " + label + " <text>");
        }
        if (!thinkProcessService.setGuardOverride(process.getId(), field, value)) {
            return EngineCommandResult.error("process not found");
        }
        boolean nowActive = "guardJudgeOverride".equals(field)
                ? notBlank(process.getGuardPromptOverride())
                : notBlank(process.getGuardJudgeOverride());
        String msg = "guard " + label + " set"
                + (nowActive ? " — runtime guard now active" : " — set the other field to activate");
        return EngineCommandResult.ok(msg, null);
    }

    private EngineCommandResult clear(ThinkProcessDocument process) {
        boolean a = thinkProcessService.setGuardOverride(
                process.getId(), "guardJudgeOverride", null);
        boolean b = thinkProcessService.setGuardOverride(
                process.getId(), "guardPromptOverride", null);
        if (!a && !b) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("runtime guard cleared", null);
    }

    private static boolean isRuntime(GuardConfig g, @Nullable String judge, @Nullable String prompt) {
        return judge != null && prompt != null
                && judge.equals(g.judge()) && prompt.equals(g.prompt());
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
