package de.mhus.vance.brain.command;

import de.mhus.vance.brain.marvin.MarvinEngine;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Read-only Marvin diagnostics — the {@code //marvin} engine-command verb:
 * everything about the tree without waking the identity. Works in BOTH
 * modes — headless runs have no identity at all, and a session-mode agent
 * turn may be busy or hung exactly when the user needs the state.
 *
 * <p>Surface:
 * <ul>
 *   <li>{@code //marvin} / {@code //marvin info} — the full picture: run
 *       state, root goal, node statistics, the current node with its
 *       phase, open inbox questions, and the root result on a terminal
 *       run.</li>
 *   <li>{@code //marvin state} — the one-liner glance:
 *       {@code live, 3 done / 2 running / 4 pending} — for quick status
 *       checks where the detail view is noise.</li>
 * </ul>
 *
 * <p>{@link #runsOnLane()} is {@code false} (Hactar/Zaphod/Vogon argument):
 * a diagnostic verb that queues behind an agent turn is useless exactly
 * when the turn hangs. Reads are snapshots from the node documents — the
 * tree is the authority on itself, never a copy kept here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarvinCommandHandler implements EngineCommandHandler {

    private static final String SUB_INFO = "info";
    private static final String SUB_STATE = "state";

    private final MarvinEngine engine;

    @Override
    public String verb() {
        return MarvinEngine.NAME;
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        if (!MarvinEngine.NAME.equals(process.getThinkEngine())) {
            return EngineCommandResult.error("Process '" + process.getName() + "' runs engine '"
                    + process.getThinkEngine() + "' — the '" + MarvinEngine.NAME
                    + "' verb needs a Marvin process.");
        }
        String[] tokens = splitFirstToken(argText(command));
        String sub = tokens[0].isEmpty() ? SUB_INFO : tokens[0].toLowerCase(Locale.ROOT);
        if (!SUB_INFO.equals(sub) && !SUB_STATE.equals(sub)) {
            return EngineCommandResult.error(
                    "Unknown subcommand '" + sub + "' — known: " + SUB_INFO + ", " + SUB_STATE);
        }
        Map<String, Object> status = engine.readTreeStatus(process);
        if (SUB_STATE.equals(sub)) {
            return EngineCommandResult.ok(renderState(status), status);
        }
        return EngineCommandResult.ok(renderInfo(status), status);
    }

    // ──────────────────── state (one-liner) ────────────────────

    private String renderState(Map<String, Object> status) {
        if (!Boolean.TRUE.equals(status.get("run"))) {
            return "no tree yet";
        }
        StringBuilder msg = new StringBuilder(String.valueOf(status.get("lifecycle")));
        msg.append(", ")
                .append(status.get("done"))
                .append(" done / ")
                .append(status.get("running"))
                .append(" running / ")
                .append(status.get("pending"))
                .append(" pending / ")
                .append(status.get("failed"))
                .append(" failed");
        return msg.toString();
    }

    // ──────────────────── info ────────────────────

    private String renderInfo(Map<String, Object> status) {
        if (!Boolean.TRUE.equals(status.get("run"))) {
            return "Marvin tree — no tree yet";
        }
        StringBuilder msg = new StringBuilder("Marvin tree — ");
        msg.append(String.valueOf(status.get("lifecycle")).toLowerCase(Locale.ROOT));
        msg.append("\nGoal: ").append(status.get("goal"));
        msg.append("\nNodes: ")
                .append(status.get("done"))
                .append(" done, ")
                .append(status.get("running"))
                .append(" running, ")
                .append(status.get("waiting"))
                .append(" waiting, ")
                .append(status.get("pending"))
                .append(" pending, ")
                .append(status.get("failed"))
                .append(" failed (")
                .append(status.get("nodeCount"))
                .append(" total)");
        if (status.get("currentNode") != null) {
            msg.append("\nCurrent node: ")
                    .append(status.get("currentNode"))
                    .append(" (phase ")
                    .append(status.get("currentPhase"))
                    .append(')');
        }
        Object openQuestions = status.get("openQuestions");
        if (openQuestions instanceof java.util.List<?> questions && !questions.isEmpty()) {
            msg.append("\nOpen questions (waiting for a human answer):");
            for (Object q : questions) {
                msg.append("\n - ").append(q);
            }
        }
        Object result = status.get("result");
        if (result != null) {
            msg.append("\nResult: ").append(truncate(String.valueOf(result), 300));
        }
        return msg.toString();
    }

    // ──────────────────── helpers ────────────────────

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
