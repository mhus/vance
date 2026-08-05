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
 *   <li>{@code clear} — drop the runtime override;</li>
 *   <li>{@code status [session] [set <key> <value> | del <key> | clear]} —
 *       inspect / edit the guard's transient scratch store
 *       ({@code loopValues}, or {@code sessionValues} with {@code session}).</li>
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
            case "inline" -> setInline(process, rest);
            case "clear" -> clear(process);
            case "status" -> status(process, rest);
            default -> EngineCommandResult.error(
                    "unknown subcommand '" + sub
                            + "' (script <path> | inline <script> | get | clear | status)");
        };
    }

    private EngineCommandResult status(ThinkProcessDocument process, String rest) {
        String r = rest.trim();
        if (r.isEmpty()) {
            return showStatus(process, /*loop*/ true, /*session*/ true);
        }
        boolean session = false;
        String[] h = splitFirstToken(r);
        if ("session".equalsIgnoreCase(h[0])) {
            session = true;
            r = h[1];
            if (r.isEmpty()) {
                return showStatus(process, false, true);
            }
            h = splitFirstToken(r);
        }
        if (session && process.getSessionId() == null) {
            return EngineCommandResult.error("process has no session — session scratch unavailable");
        }
        String op = h[0].toLowerCase(Locale.ROOT);
        String opRest = h[1];
        return switch (op) {
            case "set" -> statusSet(process, session, opRest);
            case "del", "delete", "rm" -> statusDel(process, session, opRest);
            case "clear" -> statusClear(process, session);
            default -> EngineCommandResult.error(
                    "usage: //guard status [session] [set <key> <value> | del <key> | clear]");
        };
    }

    private EngineCommandResult statusSet(
            ThinkProcessDocument process, boolean session, String opRest) {
        String[] kv = splitFirstToken(opRest);
        if (kv[0].isEmpty() || kv[1].isEmpty()) {
            return EngineCommandResult.error("set requires a key and a value: set <key> <value>");
        }
        guardService.putScratch(process, session, kv[0], kv[1]);
        return EngineCommandResult.ok(
                scope(session) + " " + kv[0] + " = " + kv[1], null);
    }

    private EngineCommandResult statusDel(
            ThinkProcessDocument process, boolean session, String opRest) {
        String key = opRest.trim();
        if (key.isEmpty()) {
            return EngineCommandResult.error("del requires a key: del <key>");
        }
        boolean removed = guardService.removeScratch(process, session, key);
        return EngineCommandResult.ok(
                scope(session) + " " + key + (removed ? " removed" : " (not set)"), null);
    }

    private EngineCommandResult statusClear(ThinkProcessDocument process, boolean session) {
        guardService.clearScratch(process, session);
        return EngineCommandResult.ok(scope(session) + " scratch cleared", null);
    }

    private EngineCommandResult showStatus(
            ThinkProcessDocument process, boolean loop, boolean session) {
        StringBuilder detail = new StringBuilder();
        int total = 0;
        if (loop) {
            total += appendScope(detail, "loop", guardService.loopScratchView(process));
        }
        if (session) {
            total += appendScope(detail, "session", guardService.sessionScratchView(process));
        }
        String summary = "loop: " + guardService.loopScratchView(process).size() + " entries"
                + (process.getSessionId() == null
                        ? "; session: (no session)"
                        : "; session: " + guardService.sessionScratchView(process).size() + " entries");
        return EngineCommandResult.ok(summary, total == 0 ? null : detail.toString().stripTrailing());
    }

    private static int appendScope(
            StringBuilder detail, String label, java.util.Map<String, Object> scratch) {
        for (java.util.Map.Entry<String, Object> e : scratch.entrySet()) {
            detail.append(label).append('.').append(e.getKey())
                    .append(" = ").append(e.getValue()).append('\n');
        }
        return scratch.size();
    }

    private static String scope(boolean session) {
        return session ? "session" : "loop";
    }

    private EngineCommandResult get(ThinkProcessDocument process) {
        String script = process.getGuardScriptOverride();
        String inline = process.getGuardScriptBodyOverride();
        int recipeGuards = (int) guardService.resolveGuards(process).stream()
                .filter(g -> !isRuntime(g, script, inline))
                .count();
        String runtime = notBlank(inline)
                ? "inline (" + inline.length() + " chars)"
                : notBlank(script) ? "script=" + script : "—";
        String state = "runtime: " + runtime + "; recipe guards: " + recipeGuards;
        return EngineCommandResult.ok(state, null);
    }

    private EngineCommandResult setScript(ThinkProcessDocument process, String value) {
        if (value.isBlank()) {
            return EngineCommandResult.error("set requires a path: //guard script <path>");
        }
        if (!thinkProcessService.setGuardOverride(process.getId(), value, null)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("guard script set — runtime guard now active", null);
    }

    private EngineCommandResult setInline(ThinkProcessDocument process, String body) {
        if (body.isBlank()) {
            return EngineCommandResult.error("inline requires a script: //guard inline <script>");
        }
        if (!thinkProcessService.setGuardOverride(process.getId(), null, body)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("guard inline script set — runtime guard now active", null);
    }

    private EngineCommandResult clear(ThinkProcessDocument process) {
        if (!thinkProcessService.setGuardOverride(process.getId(), null, null)) {
            return EngineCommandResult.error("process not found");
        }
        return EngineCommandResult.ok("runtime guard cleared", null);
    }

    private static boolean isRuntime(
            de.mhus.vance.brain.recipe.GuardConfig g,
            @Nullable String script, @Nullable String inline) {
        return (script != null && script.equals(g.scriptPath()))
                || (inline != null && inline.equals(g.scriptBody()));
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
