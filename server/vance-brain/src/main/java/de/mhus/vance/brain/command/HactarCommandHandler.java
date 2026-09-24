package de.mhus.vance.brain.command;

import de.mhus.vance.api.hactar.HactarState;
import de.mhus.vance.api.hactar.HactarStatus;
import de.mhus.vance.brain.hactar.HactarEngine;
import de.mhus.vance.brain.hactar.HactarProgressRing;
import de.mhus.vance.brain.hactar.HactarRunService;
import de.mhus.vance.brain.hactar.HactarStateStore;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Read-only Hactar diagnostics — the {@code //hactar} engine-command verb:
 * everything about a run without waking the agent. Works in BOTH modes —
 * headless runs have no identity at all, and a session-mode agent turn may
 * be busy or hung exactly when the user needs the state.
 *
 * <p>Surface:
 * <ul>
 *   <li>{@code //hactar} / {@code //hactar info} — the full picture: run
 *       state (idle/running/finished/failed + phase), script ref, elapsed
 *       time, validateBeforeRun, last result or failure with error class,
 *       and the progress-ring tail (the {@code vance.process.progress}
 *       notes the script emitted — filled in both modes, F3).</li>
 *   <li>{@code //hactar state} — the one-liner glance:
 *       {@code EXECUTING 42s, scripts/mailbot.js, running} — for quick
 *       status checks where the detail view is noise.</li>
 * </ul>
 *
 * <p>{@link #runsOnLane()} is {@code false} (Zaphod argument): a diagnostic
 * verb that queues behind an agent turn is useless exactly when the turn
 * hangs. Reads are state snapshots from the persisted engine params (the
 * run service persists on every phase transition) plus the in-memory live
 * flags — always a consistent last step.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HactarCommandHandler implements EngineCommandHandler {

    private static final String SUB_INFO = "info";
    private static final String SUB_STATE = "state";
    private static final int RESULT_PREVIEW_CHARS = 300;
    private static final int PROGRESS_TAIL = 5;

    private final HactarRunService runService;
    private final HactarStateStore stateStore;
    private final HactarProgressRing progressRing;
    private final de.mhus.vance.brain.hactar.HactarConsoleLog consoleLog;

    @Override
    public String verb() {
        return HactarEngine.NAME;
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        if (!HactarEngine.NAME.equals(process.getThinkEngine())) {
            return EngineCommandResult.error("Process '" + process.getName() + "' runs engine '"
                    + process.getThinkEngine() + "' — the '" + HactarEngine.NAME
                    + "' verb needs a Hactar process.");
        }
        String[] tokens = splitFirstToken(argText(command));
        String sub = tokens[0].isEmpty() ? SUB_INFO : tokens[0].toLowerCase(Locale.ROOT);
        if (!SUB_INFO.equals(sub) && !SUB_STATE.equals(sub)) {
            return EngineCommandResult.error(
                    "Unknown subcommand '" + sub + "' — known: " + SUB_INFO + ", " + SUB_STATE);
        }
        HactarState state = stateStore.load(process);
        boolean running = runService.isRunning(process.getId());
        if (SUB_STATE.equals(sub)) {
            return EngineCommandResult.ok(renderState(process.getId(), state, running), stateValue(state, running));
        }
        return EngineCommandResult.ok(renderInfo(process.getId(), state, running), infoValue(state, running));
    }

    // ──────────────────── state (one-liner) ────────────────────

    /** The glance view: phase, elapsed time, script, run state — one line. */
    private String renderState(String processId, HactarState s, boolean running) {
        StringBuilder msg = new StringBuilder();
        msg.append(s.getStatus() == null ? "?" : s.getStatus());
        if (running) {
            Long startedAt = runService.runStartedAtMs(processId);
            msg.append(startedAt == null ? "" : " " + ((System.currentTimeMillis() - startedAt) / 1000) + "s");
        }
        if (s.getScriptRef() != null) {
            msg.append(", ").append(s.getScriptRef());
        }
        msg.append(", ").append(describeRun(s, running));
        return msg.toString();
    }

    private Map<String, Object> stateValue(HactarState s, boolean running) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("phase", s.getStatus() == null ? null : s.getStatus().name());
        value.put("running", running);
        value.put("scriptRef", s.getScriptRef());
        value.put("failureReason", s.getFailureReason());
        value.put("chatIdentity", s.isChatIdentity());
        return value;
    }

    // ──────────────────── info ────────────────────

    private String renderInfo(String processId, HactarState s, boolean running) {
        StringBuilder msg = new StringBuilder("Hactar run — ");
        msg.append(describeRun(s, running));
        msg.append("\nScript: ").append(s.getScriptRef() == null ? "(not set — no run kicked yet)" : s.getScriptRef());
        msg.append("\nPhase: ").append(s.getStatus() == null ? "?" : s.getStatus());
        if (running) {
            Long startedAt = runService.runStartedAtMs(processId);
            if (startedAt != null) {
                msg.append(" (")
                        .append((System.currentTimeMillis() - startedAt) / 1000)
                        .append("s elapsed)");
            }
        }
        msg.append("\nDeep-validate before run: ").append(s.isValidateBeforeRun());
        if (s.getExecutionResult() != null) {
            msg.append("\nResult: ").append(truncate(String.valueOf(s.getExecutionResult()), RESULT_PREVIEW_CHARS));
        }
        if (s.getFailureReason() != null) {
            msg.append("\nFailure: ").append(truncate(s.getFailureReason(), RESULT_PREVIEW_CHARS));
            if (s.getExecutionErrorClass() != null) {
                msg.append(" (errorClass=").append(s.getExecutionErrorClass()).append(')');
            }
        }
        if (!s.getValidationIssues().isEmpty()) {
            msg.append("\nValidation issues: ").append(s.getValidationIssues().size());
        }
        List<HactarProgressRing.Entry> tail = progressRing.tail(processId, PROGRESS_TAIL);
        if (!tail.isEmpty()) {
            msg.append("\nProgress notes:");
            for (HactarProgressRing.Entry e : tail) {
                msg.append("\n - ").append(e.text());
            }
        }
        // Live console first (Live-Fund 5): mid-run the line ring is the
        // current output; the persisted consoleTail only exists after the
        // terminal. Fallback for finished runs whose ring died with the pod.
        String liveConsole = consoleLog.renderTail(processId, 10);
        if (!liveConsole.isEmpty()) {
            msg.append("\nConsole output (live):\n```\n").append(liveConsole).append("\n```");
        } else {
            String console = de.mhus.vance.brain.hactar.ConsoleExcerpt.of(s.getConsoleTail(), 10, 2000);
            if (!console.isEmpty()) {
                msg.append("\nConsole output (tail):\n```\n").append(console).append("\n```");
            }
        }
        return msg.toString();
    }

    private Map<String, Object> infoValue(HactarState s, boolean running) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("running", running);
        value.put("phase", s.getStatus() == null ? null : s.getStatus().name());
        value.put("scriptRef", s.getScriptRef());
        value.put("validateBeforeRun", s.isValidateBeforeRun());
        value.put("chatIdentity", s.isChatIdentity());
        value.put("runStartedAtMs", s.getRunStartedAtMs());
        value.put("executionDurationMs", s.getExecutionDurationMs());
        value.put("executionResult", s.getExecutionResult());
        value.put("executionError", s.getExecutionError());
        value.put("executionErrorClass", s.getExecutionErrorClass());
        value.put("failureReason", s.getFailureReason());
        value.put("validationIssues", s.getValidationIssues().size());
        value.put("consoleTail", s.getConsoleTail());
        return value;
    }

    // ──────────────────── helpers ────────────────────

    private static String describeRun(HactarState s, boolean running) {
        if (running) {
            return "running";
        }
        if (s.getStatus() == HactarStatus.DONE) {
            return "finished (" + s.getExecutionDurationMs() + "ms)";
        }
        if (s.getStatus() == HactarStatus.FAILED) {
            return "failed";
        }
        if (s.getStatus() == HactarStatus.READY) {
            return "no run yet";
        }
        return "idle (interrupted mid-run)";
    }

    private static String truncate(String s, int max) {
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
