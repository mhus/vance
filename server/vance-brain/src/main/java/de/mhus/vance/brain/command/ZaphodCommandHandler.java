package de.mhus.vance.brain.command;

import de.mhus.vance.api.zaphod.HeadStatus;
import de.mhus.vance.api.zaphod.ZaphodHead;
import de.mhus.vance.api.zaphod.ZaphodMode;
import de.mhus.vance.api.zaphod.ZaphodState;
import de.mhus.vance.brain.zaphod.ZaphodEngine;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only Zaphod diagnostics — the {@code //zaphod} engine-command
 * verb. Guard-style: one verb with subcommands parsed from the raw
 * {@code text} arg; an empty subcommand falls back to {@code info}
 * (analog {@code //guard} falling back to {@code get}).
 *
 * <p>Surface (see {@code planning/zaphod-session-mode.md} §7a):
 * <ul>
 *   <li>{@code //zaphod} / {@code //zaphod info} — the head list:
 *       pattern, mode, turn/round cursor, per-head status, and the
 *       last synthesis title/summary.</li>
 *   <li>{@code //zaphod info <head>} — one head's details: persona
 *       preview, recipe, status, failure reason, last-reply preview,
 *       and the head process name (pointer for the runs view /
 *       {@code process_history}).</li>
 * </ul>
 *
 * <p>{@link #runsOnLane()} is {@code false}: the handler only reads.
 * That is the diagnostic case the lane opt-out exists for — on the
 * lane, the verb would block exactly when a head turn hangs, i.e.
 * exactly when the user needs to know which head is hanging. Zaphod
 * state writes are atomic Mongo replaces, so a bypassed read always
 * sees a consistent last-persisted step. Mutating follow-up verbs
 * ({@code //zaphod add}, {@code //zaphod reset} — zaphod-engine.md
 * §14) will run on the lane instead.
 *
 * <p>The handler deserialises {@code engineParams.zaphodState} into
 * {@link ZaphodState} directly — no dependency on the engine class
 * beyond the state-key constant. Unknown target engine, missing
 * state, or an unknown head are defined ERROR outcomes (dispatch
 * contract: no crash).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ZaphodCommandHandler implements EngineCommandHandler {

    private static final String SUB_INFO = "info";
    private static final int PERSONA_PREVIEW_CHARS = 200;
    private static final int REPLY_PREVIEW_CHARS = 300;

    private final ObjectMapper objectMapper;

    @Override
    public String verb() {
        return ZaphodEngine.NAME;
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        if (!ZaphodEngine.NAME.equals(process.getThinkEngine())) {
            return EngineCommandResult.error("Process '" + process.getName() + "' runs engine '"
                    + process.getThinkEngine() + "' — the '" + ZaphodEngine.NAME + "' verb needs a Zaphod process.");
        }
        String[] head = splitFirstToken(argText(command));
        String sub = head[0].isEmpty() ? SUB_INFO : head[0].toLowerCase(Locale.ROOT);
        String rest = head[1];
        if (!SUB_INFO.equals(sub)) {
            return EngineCommandResult.error("Unknown subcommand '" + sub + "' — known: " + SUB_INFO);
        }
        ZaphodState state = loadState(process);
        if (state == null) {
            return EngineCommandResult.error(
                    "No Zaphod state on process '" + process.getName() + "' — the engine has not started yet.");
        }
        return rest.isEmpty() ? infoList(process, state) : infoHead(process, state, rest);
    }

    // ──────────────────── info (list) ────────────────────

    private EngineCommandResult infoList(ThinkProcessDocument process, ZaphodState state) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", state.getMode() == null ? null : state.getMode().name());
        value.put(
                "pattern",
                state.getPattern() == null ? null : state.getPattern().name());
        value.put("turnIndex", state.getTurnIndex());
        value.put("currentRound", state.getCurrentRound());
        value.put("maxRounds", state.getMaxRounds());
        value.put("currentHeadIndex", state.getCurrentHeadIndex());

        List<Map<String, Object>> heads = new ArrayList<>();
        StringBuilder msg = new StringBuilder();
        msg.append("Zaphod council — pattern=")
                .append(state.getPattern())
                .append(" mode=")
                .append(state.getMode())
                .append(" turn=")
                .append(state.getTurnIndex())
                .append(" (round ")
                .append(state.getCurrentRound() + 1)
                .append('/')
                .append(state.getMaxRounds())
                .append(", next head ")
                .append(Math.min(
                        state.getCurrentHeadIndex() + 1,
                        Math.max(state.getHeads().size(), 1)))
                .append('/')
                .append(state.getHeads().size())
                .append(')');
        msg.append("\nHeads:");
        for (ZaphodHead h : state.getHeads()) {
            Map<String, Object> entry = headEntry(h);
            heads.add(entry);
            msg.append("\n - ")
                    .append(h.getName())
                    .append(": ")
                    .append(h.getStatus())
                    .append(", recipe=")
                    .append(h.getRecipe())
                    .append(", replies=")
                    .append(h.getReplies() == null ? 0 : h.getReplies().size());
            if (h.getSpawnedProcessId() != null) {
                msg.append(", process=").append(headProcessName(process, h));
            }
            if (h.getStatus() == HeadStatus.FAILED && h.getFailureReason() != null) {
                msg.append("\n   failure: ").append(truncate(h.getFailureReason(), REPLY_PREVIEW_CHARS));
            }
        }
        value.put("heads", heads);
        if (state.getSynthesisTitle() != null) {
            msg.append("\nLast synthesis: \"").append(state.getSynthesisTitle()).append('"');
            if (state.getSynthesisSummary() != null) {
                msg.append(" — ").append(state.getSynthesisSummary());
            }
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("title", state.getSynthesisTitle());
            last.put("summary", state.getSynthesisSummary());
            value.put("lastSynthesis", last);
        }
        if (state.getFailureReason() != null) {
            value.put("failureReason", state.getFailureReason());
        }
        return EngineCommandResult.ok(msg.toString(), value);
    }

    // ──────────────────── info <head> ────────────────────

    private EngineCommandResult infoHead(ThinkProcessDocument process, ZaphodState state, String name) {
        ZaphodHead head = null;
        for (ZaphodHead h : state.getHeads()) {
            if (h.getName().equalsIgnoreCase(name.trim())) {
                head = h;
                break;
            }
        }
        if (head == null) {
            return EngineCommandResult.error("Unknown head '" + name.trim() + "' — valid heads: " + headNames(state));
        }
        String lastReply = head.getLastReply();
        Map<String, Object> value = headEntry(head);
        value.put("processName", head.getSpawnedProcessId() == null ? null : headProcessName(process, head));
        value.put("persona", head.getPersona());
        value.put("lastReplyPreview", lastReply == null ? null : truncate(lastReply, REPLY_PREVIEW_CHARS));

        StringBuilder msg = new StringBuilder();
        msg.append("Head ")
                .append(head.getName())
                .append(" (recipe=")
                .append(head.getRecipe())
                .append(", status=")
                .append(head.getStatus())
                .append(')');
        if (head.getSpawnedProcessId() != null) {
            msg.append("\nProcess: ").append(headProcessName(process, head));
        }
        if (head.getPersona() != null && !head.getPersona().isBlank()) {
            msg.append("\nPersona: ").append(truncate(head.getPersona().trim(), PERSONA_PREVIEW_CHARS));
        }
        if (lastReply != null && !lastReply.isBlank()) {
            msg.append("\nLast reply (")
                    .append(lastReply.length())
                    .append(" chars): ")
                    .append(truncate(lastReply, REPLY_PREVIEW_CHARS));
        }
        if (head.getFailureReason() != null) {
            msg.append("\nFailure: ").append(head.getFailureReason());
        }
        return EngineCommandResult.ok(msg.toString(), value);
    }

    // ──────────────────── helpers ────────────────────

    private Map<String, Object> headEntry(ZaphodHead h) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", h.getName());
        entry.put("status", h.getStatus() == null ? null : h.getStatus().name());
        entry.put("recipe", h.getRecipe());
        entry.put("replyCount", h.getReplies() == null ? 0 : h.getReplies().size());
        entry.put("spawnedProcessId", h.getSpawnedProcessId());
        if (h.getFailureReason() != null) {
            entry.put("failureReason", h.getFailureReason());
        }
        return entry;
    }

    private static String headNames(ZaphodState state) {
        StringBuilder sb = new StringBuilder();
        for (ZaphodHead h : state.getHeads()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(h.getName());
        }
        return sb.toString();
    }

    /** Mirrors the spawn naming from {@code ZaphodEngine.driveHeadForRound}. */
    private static String headProcessName(ThinkProcessDocument process, ZaphodHead head) {
        return "zaphod-" + process.getId() + "-" + head.getName();
    }

    /** Fields a persisted pre-session-mode state lacks arrive as
     *  null — normalise for display (mode→BATCH, turnIndex→0),
     *  mirroring the engine's loadState.
     */
    private static ZaphodState normalise(ZaphodState state) {
        if (state.getMode() == null) {
            state.setMode(ZaphodMode.BATCH);
        }
        if (state.getTurnIndex() == null) {
            state.setTurnIndex(0);
        }
        return state;
    }

    private @Nullable ZaphodState loadState(ThinkProcessDocument process) {
        Map<String, Object> p = process.getEngineParams();
        if (p == null) return null;
        Object raw = p.get(ZaphodEngine.STATE_KEY);
        if (!(raw instanceof Map<?, ?>)) return null;
        try {
            return normalise(objectMapper.convertValue(raw, ZaphodState.class));
        } catch (RuntimeException e) {
            log.warn("Zaphod state deserialisation failed for process '{}': {}", process.getId(), e.toString());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        String t = s.trim();
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
