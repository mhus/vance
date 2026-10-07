package de.mhus.vance.brain.command;

import de.mhus.vance.api.magrathea.MagratheaProcessDto;
import de.mhus.vance.api.magrathea.MagratheaRunStatus;
import de.mhus.vance.brain.magrathea.MagratheaGateChatAnswerService;
import de.mhus.vance.brain.vogon.VogonEngine;
import de.mhus.vance.shared.magrathea.MagratheaStateProjector;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Read-only Vogon diagnostics — the {@code //vogon} engine-command verb:
 * everything about a run without waking the identity. Works in BOTH modes —
 * headless runs have no identity at all, and a session-mode agent turn may
 * be busy or hung exactly when the user needs the state.
 *
 * <p>Surface:
 * <ul>
 *   <li>{@code //vogon} / {@code //vogon info} — the full picture: run
 *       status, plan name, current state, elapsed time, the result of a
 *       terminal run, and the open gate when the run is waiting at one.</li>
 *   <li>{@code //vogon state} — the one-liner glance:
 *       {@code RUNNING, 'waterfall', review} — for quick status checks
 *       where the detail view is noise.</li>
 * </ul>
 *
 * <p>{@link #runsOnLane()} is {@code false} (Hactar/Zaphod argument): a
 * diagnostic verb that queues behind an agent turn is useless exactly when
 * the turn hangs. Reads are snapshots from the journal projection — the
 * run is the authority on itself, never a copy kept here.
 */
@Component
@ConditionalOnProperty(value = "vance.services.magrathea", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
@Slf4j
public class VogonCommandHandler implements EngineCommandHandler {

    private static final String SUB_INFO = "info";
    private static final String SUB_STATE = "state";
    private static final int RESULT_PREVIEW_CHARS = 300;

    private final MagratheaStateProjector projector;
    private final MagratheaGateChatAnswerService gateChatAnswerService;

    @Override
    public String verb() {
        return VogonEngine.NAME;
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        if (!VogonEngine.NAME.equals(process.getThinkEngine())) {
            return EngineCommandResult.error("Process '" + process.getName() + "' runs engine '"
                    + process.getThinkEngine() + "' — the '" + VogonEngine.NAME
                    + "' verb needs a Vogon process.");
        }
        String[] tokens = splitFirstToken(argText(command));
        String sub = tokens[0].isEmpty() ? SUB_INFO : tokens[0].toLowerCase(Locale.ROOT);
        if (!SUB_INFO.equals(sub) && !SUB_STATE.equals(sub)) {
            return EngineCommandResult.error(
                    "Unknown subcommand '" + sub + "' — known: " + SUB_INFO + ", " + SUB_STATE);
        }
        String runId = runId(process);
        Optional<MagratheaProcessDto> run = runId == null
                ? Optional.empty()
                : projector.project(process.getTenantId(), process.getProjectId(), runId);
        if (SUB_STATE.equals(sub)) {
            return EngineCommandResult.ok(renderState(runId, run), stateValue(runId, run));
        }
        return EngineCommandResult.ok(renderInfo(process, runId, run), infoValue(process, runId, run));
    }

    // ──────────────────── state (one-liner) ────────────────────

    /** The glance view: run status, plan name, current state — one line. */
    private String renderState(@Nullable String runId, Optional<MagratheaProcessDto> run) {
        if (runId == null) return "no run yet";
        StringBuilder msg = new StringBuilder(describeRun(run));
        run.ifPresent(dto -> {
            if (dto.getWorkflowName() != null) msg.append(", ").append(dto.getWorkflowName());
            if (dto.getCurrentState() != null) msg.append(", ").append(dto.getCurrentState());
        });
        return msg.toString();
    }

    private Map<String, Object> stateValue(@Nullable String runId, Optional<MagratheaProcessDto> run) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflowRunId", runId);
        value.put(
                "status",
                run.map(dto -> dto.getStatus() == null ? null : dto.getStatus().name())
                        .orElse(null));
        return value;
    }

    // ──────────────────── info ────────────────────

    private String renderInfo(ThinkProcessDocument process, @Nullable String runId, Optional<MagratheaProcessDto> run) {
        StringBuilder msg = new StringBuilder("Vogon run — ");
        if (runId == null) {
            msg.append("no run yet");
            msg.append("\nPlan: (not set — no run started yet)");
            return msg.toString();
        }
        msg.append(describeRun(run));
        run.ifPresent(dto -> {
            msg.append("\nPlan: ").append(dto.getWorkflowName());
            msg.append("\nRun: ").append(runId);
            if (dto.getCurrentState() != null) {
                msg.append("\nCurrent state: ").append(dto.getCurrentState());
            }
            if (dto.getCreatedAt() != null) {
                msg.append("\nElapsed: ")
                        .append(java.time.Duration.between(dto.getCreatedAt(), java.time.Instant.now())
                                .getSeconds())
                        .append('s');
            }
            if (isTerminal(dto.getStatus())
                    && dto.getResult() != null
                    && !dto.getResult().isEmpty()) {
                msg.append("\nResult: ").append(truncate(String.valueOf(dto.getResult()), RESULT_PREVIEW_CHARS));
            }
        });
        gateChatAnswerService.findOpenGateItem(process.getTenantId(), runId).ifPresent(item -> {
            msg.append("\nOpen gate: ").append(item.getType());
            if (item.getTitle() != null && !item.getTitle().isBlank()) {
                msg.append(" — ").append(item.getTitle());
            }
            msg.append(" (waiting for a human answer)");
        });
        return msg.toString();
    }

    private Map<String, Object> infoValue(
            ThinkProcessDocument process, @Nullable String runId, Optional<MagratheaProcessDto> run) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("workflowRunId", runId);
        value.put(
                "status",
                run.map(dto -> dto.getStatus() == null ? null : dto.getStatus().name())
                        .orElse(null));
        run.ifPresent(dto -> {
            value.put("workflowName", dto.getWorkflowName());
            value.put("currentState", dto.getCurrentState());
            value.put("result", dto.getResult());
        });
        value.put("chatIdentity", VogonEngine.chatIdentity(process));
        return value;
    }

    // ──────────────────── helpers ────────────────────

    private static String describeRun(Optional<MagratheaProcessDto> run) {
        if (run.isEmpty()) return "no journal";
        MagratheaRunStatus status = run.get().getStatus();
        if (status == null) return "unknown";
        return switch (status) {
            case RUNNING -> "running";
            case PAUSED -> "paused";
            case DONE -> "finished";
            case FAILED -> "failed";
            case TERMINATED -> "stopped";
        };
    }

    private static boolean isTerminal(@Nullable MagratheaRunStatus status) {
        return status == MagratheaRunStatus.DONE
                || status == MagratheaRunStatus.FAILED
                || status == MagratheaRunStatus.TERMINATED;
    }

    private static @Nullable String runId(ThinkProcessDocument process) {
        Map<String, Object> params = process.getEngineParams();
        Object raw = params == null ? null : params.get(VogonEngine.PARAM_RUN_ID);
        return raw instanceof String s && !s.isBlank() ? s : null;
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
