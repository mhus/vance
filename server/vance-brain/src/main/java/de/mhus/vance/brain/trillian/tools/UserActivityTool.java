package de.mhus.vance.brain.trillian.tools;

import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.brain.trillian.TrillianWakeupService;
import de.mhus.vance.brain.trillian.nature.TrillianNatureRegistry;
import de.mhus.vance.shared.chat.ChatMessageDocument;
import de.mhus.vance.shared.chat.ChatMessageService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The "what are you doing right now?" snapshot — the activity view of the
 * paired Trillian User loop, assembled from live state.
 *
 * <p>Where {@code user_status} answers the narrow question (is the loop
 * running, how deep is its inbox), this tool answers the human one: what
 * is it working on, what did it just do, when does it next look around.
 * Everything here is read in <em>this</em> turn — the snapshot is the
 * only thing the caller may state as fact (see the Control prompt's
 * honesty rules), so it carries the recent activity explicitly instead
 * of asking the LLM to remember it.
 */
@Component
@RequiredArgsConstructor
public class UserActivityTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of());

    /** Recent chat lines returned to the caller — enough for "what just happened". */
    private static final int RECENT_LINES = 8;

    /** Per-line truncation for the activity feed. */
    private static final int LINE_LIMIT = 240;

    private final TrillianInternalApi api;
    private final TrillianWakeupService wakeupService;
    private final ThinkProcessService thinkProcessService;
    private final ChatMessageService chatMessageService;
    private final TrillianNatureRegistry natureRegistry;

    @Override
    public String name() {
        return "user_activity";
    }

    @Override
    public String description() {
        return "What the paired Trillian User loop is doing right now: "
                + "runtime status, the task workers it has running (with "
                + "target project and age), what it did recently, and when "
                + "it next checks in by itself. Use this when the human asks "
                + "what is going on, what it is working on, or what it just "
                + "did. Everything returned is live state — report it as "
                + "returned, never from memory of an earlier turn.";
    }

    @Override
    public boolean primary() {
        return true;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Set<String> labels() {
        return Set.of("read-only");
    }

    @Override
    public Set<String> requiresEngineRoles() {
        return Set.of(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx.processId() == null) {
            throw new ToolException("user_activity requires a process scope");
        }
        ThinkProcessDocument peer = api.findPeer(ctx.processId())
                .orElseThrow(
                        () -> new ToolException("No Trillian User peer process found — this tool is only available "
                                + "inside a Trillian-Control session"));
        TrillianInternalApi.PeerStateSnapshot snap = api.snapshotPeerState(peer);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trillianUserName", trillianNameOf(peer));
        out.put("callName", callNameOf(peer));
        out.put("status", snap.status() == null ? null : snap.status().name());
        out.put("pendingInbox", snap.pendingInboxCount());
        out.put("nextSelfCheck", nextSelfCheckOf(peer));
        out.put("workers", workersOf(peer));
        out.put("recentActivity", recentActivityOf(peer));
        return out;
    }

    private @Nullable String trillianNameOf(ThinkProcessDocument peer) {
        Object name = peer.getEngineParams() == null
                ? null
                : peer.getEngineParams().get(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        return name == null ? null : name.toString();
    }

    private String callNameOf(ThinkProcessDocument peer) {
        String nature = TrillianSessionBootstrapper.readNature(peer);
        return natureRegistry.resolve(nature).callName(TrillianInternalApi.readAttributes(peer));
    }

    /**
     * The armed self-check as ISO timestamp, or {@code null} when the
     * loop has no appointment (a worker is running, or it was paused).
     */
    private @Nullable String nextSelfCheckOf(ThinkProcessDocument peer) {
        Instant next = wakeupService.nextWakeupAt(peer);
        return next == null ? null : next.toString();
    }

    /** Live task workers spawned by the loop, newest first. */
    private List<Map<String, Object>> workersOf(ThinkProcessDocument peer) {
        List<Map<String, Object>> workers = new ArrayList<>();
        Instant now = Instant.now();
        for (ThinkProcessDocument worker : thinkProcessService.findByParentProcessId(peer.getId())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", worker.getName());
            row.put(
                    "status",
                    worker.getStatus() == null ? null : worker.getStatus().name());
            row.put("projectId", worker.getProjectId());
            if (worker.getTitle() != null && !worker.getTitle().isBlank()) {
                row.put("title", worker.getTitle());
            }
            if (worker.getGoal() != null && !worker.getGoal().isBlank()) {
                row.put("goal", truncate(worker.getGoal(), LINE_LIMIT));
            }
            if (worker.getCreatedAt() != null) {
                row.put(
                        "ageSeconds",
                        Duration.between(worker.getCreatedAt(), now).getSeconds());
            }
            workers.add(row);
        }
        return workers;
    }

    /**
     * The tail of the loop's own chat log — what it did recently. This is
     * what the human is really asking for, and reading it here is what
     * keeps the answer honest: the LLM reports the lines it was given,
     * not the ones it thinks it remembers.
     */
    private List<Map<String, Object>> recentActivityOf(ThinkProcessDocument peer) {
        List<ChatMessageDocument> all =
                chatMessageService.activeHistoryWithInterim(peer.getTenantId(), peer.getSessionId(), peer.getId());
        List<ChatMessageDocument> tail = all.subList(Math.max(0, all.size() - RECENT_LINES), all.size());
        List<Map<String, Object>> rows = new ArrayList<>(tail.size());
        for (ChatMessageDocument m : tail) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", m.getRole() == null ? null : m.getRole().name());
            if (m.getCreatedAt() != null) {
                row.put("at", m.getCreatedAt().toString());
            }
            row.put("text", truncate(m.getContent(), LINE_LIMIT));
            rows.add(row);
        }
        return rows;
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String flat = text.strip();
        return flat.length() <= limit ? flat : flat.substring(0, limit) + "…";
    }
}
