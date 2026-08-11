package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.brain.command.EngineCommand;
import de.mhus.vance.brain.command.EngineCommandHandler;
import de.mhus.vance.brain.command.EngineCommandResult;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code //trillian <subcommand>} — direct control over the paired
 * Trillian worker, bypassing the LLM.
 *
 * <p>The {@code user_*} tools do the same things, but only when Control
 * is answering and picks the right one. This channel is for the human:
 * deterministic, free of tokens, and available while a turn is stuck —
 * which is when you most want to look or to stop something.
 *
 * <p><b>Why it does not run on the addressed lane.</b> Every subcommand
 * either reads or targets the <em>peer</em> process, never the Control
 * process it is addressed to. Mutations serialize on the peer's lane
 * (inside {@link TrillianInternalApi}); the Control lane is not involved
 * and must not be waited on — a stop that queues behind the turn it is
 * meant to interrupt is no stop.
 *
 * <p>Deliberately shares {@link TrillianInternalApi} with the
 * {@code user_*} tools: two entrances, one implementation. Anything else
 * drifts, and then you debug the controls instead of the thing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianCommandHandler implements EngineCommandHandler {

    private final TrillianInternalApi api;
    private final ThinkProcessService thinkProcessService;

    @Override
    public String verb() {
        return "trillian";
    }

    @Override
    public boolean runsOnLane() {
        return false;
    }

    @Override
    public EngineCommandResult handle(ThinkProcessDocument process, EngineCommand command) {
        String[] head = splitFirstToken(argText(command));
        String sub = head[0].isEmpty() ? "info" : head[0].toLowerCase(Locale.ROOT);

        Optional<ThinkProcessDocument> peerOpt = api.findPeer(process.getId());
        if (peerOpt.isEmpty()) {
            return EngineCommandResult.error(
                    "No Trillian worker paired with this process — //trillian only works "
                            + "in a Trillian-Control session");
        }
        ThinkProcessDocument peer = peerOpt.get();

        return switch (sub) {
            case "info" -> info(process, peer);
            case "stop" -> stop(peer);
            case "continue", "resume" -> resume(peer);
            case "clear" -> clear(peer);
            default -> EngineCommandResult.error(
                    "unknown subcommand '" + sub + "' (info | stop | continue | clear)");
        };
    }

    /**
     * The state you would otherwise reconstruct from the brain log:
     * which account the worker runs as, what it is doing, what it may
     * reach, and which workers are alive.
     */
    private EngineCommandResult info(ThinkProcessDocument control, ThinkProcessDocument peer) {
        TrillianInternalApi.PeerStateSnapshot snap = api.snapshotPeerState(peer);
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> controlInfo = new LinkedHashMap<>();
        controlInfo.put("processId", control.getId());
        controlInfo.put("status", nameOf(control.getStatus()));
        controlInfo.put("sessionId", control.getSessionId());
        controlInfo.put("projectId", control.getProjectId());
        controlInfo.put("nature", param(control, TrillianSessionBootstrapper.PARAM_NATURE));
        out.put("control", controlInfo);

        Map<String, Object> workerInfo = new LinkedHashMap<>();
        workerInfo.put("account", param(peer, TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME));
        workerInfo.put("processId", snap.processId());
        workerInfo.put("processName", snap.name());
        workerInfo.put("sessionId", peer.getSessionId());
        workerInfo.put("status", nameOf(snap.status()));
        workerInfo.put("pendingInbox", snap.pendingInboxCount());
        workerInfo.put("attributes", TrillianInternalApi.readAttributes(peer));
        out.put("worker", workerInfo);

        out.put("workers", spawnedWorkers(peer));
        return EngineCommandResult.ok(renderSummary(workerInfo, out), out);
    }

    /**
     * Live children in the worker's session, i.e. the per-task workers it
     * spawned. The worker loop itself is filtered out — it is already
     * reported as {@code worker}.
     */
    private List<Map<String, Object>> spawnedWorkers(ThinkProcessDocument peer) {
        List<Map<String, Object>> workers = new ArrayList<>();
        List<ThinkProcessDocument> inSession = thinkProcessService.findBySession(
                peer.getTenantId(), peer.getSessionId());
        for (ThinkProcessDocument p : inSession) {
            if (p.getId().equals(peer.getId()) || p.getStatus() == ThinkProcessStatus.CLOSED) {
                continue;
            }
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("name", p.getName());
            w.put("processId", p.getId());
            // The target project is what makes a cross-project spawn
            // visible — it differs from the worker session's own project.
            w.put("projectId", p.getProjectId());
            w.put("status", nameOf(p.getStatus()));
            w.put("engine", p.getThinkEngine());
            w.put("age", age(p.getCreatedAt()));
            workers.add(w);
        }
        return workers;
    }

    private EngineCommandResult stop(ThinkProcessDocument peer) {
        try {
            ThinkProcessStatus now = api.pausePeer(peer);
            return EngineCommandResult.ok(
                    "Worker '" + peer.getName() + "' is " + now
                            + ". Spawned workers keep running — //trillian info shows them.",
                    Map.of("status", nameOf(now)));
        } catch (RuntimeException e) {
            return EngineCommandResult.error("stop failed: " + e.getMessage());
        }
    }

    private EngineCommandResult resume(ThinkProcessDocument peer) {
        try {
            ThinkProcessStatus now = api.resumePeer(peer);
            return EngineCommandResult.ok(
                    "Worker '" + peer.getName() + "' is " + now + ".",
                    Map.of("status", nameOf(now)));
        } catch (RuntimeException e) {
            return EngineCommandResult.error("continue failed: " + e.getMessage());
        }
    }

    private EngineCommandResult clear(ThinkProcessDocument peer) {
        int dropped = api.clearPending(peer.getId());
        return EngineCommandResult.ok(
                "Dropped " + dropped + " queued message(s) from the worker's inbox.",
                Map.of("dropped", dropped));
    }

    /** One line for the terminal; the structured value carries the rest. */
    private String renderSummary(Map<String, Object> worker, Map<String, Object> all) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workers = (List<Map<String, Object>>) all.get("workers");
        return "worker " + worker.get("account") + " " + worker.get("status")
                + ", inbox " + worker.get("pendingInbox")
                + ", " + workers.size() + " running task worker(s)";
    }

    private @org.jspecify.annotations.Nullable Object param(
            ThinkProcessDocument process, String key) {
        return process.getEngineParams() == null ? null : process.getEngineParams().get(key);
    }

    private static @org.jspecify.annotations.Nullable String nameOf(
            @org.jspecify.annotations.Nullable ThinkProcessStatus status) {
        return status == null ? null : status.name();
    }

    private static @org.jspecify.annotations.Nullable String age(
            @org.jspecify.annotations.Nullable Instant since) {
        if (since == null) {
            return null;
        }
        Duration d = Duration.between(since, Instant.now());
        if (d.toHours() > 0) {
            return d.toHours() + "h" + (d.toMinutesPart()) + "m";
        }
        if (d.toMinutes() > 0) {
            return d.toMinutes() + "m";
        }
        return Math.max(0, d.toSeconds()) + "s";
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
