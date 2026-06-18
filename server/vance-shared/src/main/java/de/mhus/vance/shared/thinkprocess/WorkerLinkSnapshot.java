package de.mhus.vance.shared.thinkprocess;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.eddie.ChannelMode;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.thinkprocess.ProcessMode;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.api.thinkprocess.TodoItem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Per-worker snapshot a parent process keeps for every child it
 * spawned. Embedded as an array on
 * {@code ThinkProcessDocument.workerLinks}, one entry per tracked
 * worker. The {@code workerProcessId} is the upsert key — calls to
 * {@link ThinkProcessService#upsertWorkerLink} merge by this id and
 * replace the whole record (no partial-merge semantics).
 *
 * <p>The class is split into three logical field-groups that
 * different consumers populate:
 *
 * <ul>
 *   <li><b>Identity + lifecycle</b> ({@code workerProcessId},
 *       {@code workerProcessName}, {@code workerTenantId},
 *       {@code workerProjectName}, {@code workerSessionId},
 *       {@code workerStatus}, {@code lastSeen}) — every parent
 *       engine populates these. Arthur's pointer + active-workers
 *       block read from here. See {@code planning/process-engine-reply-channel.md} §9.</li>
 *   <li><b>Eddie working-WS</b> ({@code workerPodAddress},
 *       {@code channelMode}) — Eddie-only. The
 *       {@code WebSocketClient}-pool uses these to (re)open the
 *       Working WS after pod reassignment. Arthur leaves them null.
 *       See {@code specification/eddie-engine.md} §8.1.</li>
 *   <li><b>Eddie plan-mirror + working-memory</b>
 *       ({@code workerMode}, {@code workerTodos}, {@code planVersion},
 *       {@code triageSummary}, {@code lastCriticality},
 *       {@code lastInboxItemId}) — Eddie-only. See
 *       {@code planning/eddie-moderator-erweiterung.md} (triage) and
 *       {@code planning/eddie-plan-mode.md} (plan-mirror + fusion).</li>
 * </ul>
 *
 * <p>Engines that don't use Eddie-specific fields just leave them
 * {@code null}/default. The JSON envelope is null-tolerant; Mongo
 * stores only the populated fields when {@code @JsonInclude(NON_NULL)}
 * + Spring Data MongoDB mapping kick in.
 *
 * <p>The class lives in the neutral {@code shared.thinkprocess}
 * package as of the workerLinks-consolidation refactor — pre-refactor
 * it sat under {@code shared.eddie} because Eddie was the only
 * consumer; today Arthur populates the identity-group from her
 * {@link de.mhus.vance.brain.arthur} engine (other engines may follow).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkerLinkSnapshot {

    // === Identity + connection ===

    /** Worker {@code ThinkProcessDocument._id}. Upsert key. */
    private String workerProcessId = "";

    /** Worker {@code ThinkProcessDocument.name} — for prompt-render. */
    private String workerProcessName = "";

    /**
     * Tenant the worker belongs to. Needed to construct the Working-WS
     * URL ({@code /brain/{tenant}/ws}) — without it the connection
     * lands on a 404 path. Cross-tenant observation is rejected by
     * {@link de.mhus.vance.brain.tools.eddie.ProcessObserveTool}, so
     * this always matches the caller's tenant.
     */
    private String workerTenantId = "";

    /**
     * Worker {@code ProjectDocument.name} — for ID-prefix in the
     * fusion-rendered plan ({@code <engine>-<projectName>/<localId>}).
     */
    private String workerProjectName = "";

    /** Worker session id — for reconnect to the Working WS. */
    private String workerSessionId = "";

    /**
     * Address of the worker-project's home pod ({@code ipv4:port} per
     * memory note). Used by {@code EngineWsClient} to (re)open the
     * Working WS without re-querying the project manager. Eddie-only;
     * Arthur leaves this empty.
     */
    private String workerPodAddress = "";

    @Builder.Default
    private ChannelMode channelMode = ChannelMode.MILESTONES;

    /**
     * Last time the parent saw a frame / event from this worker.
     * Drives stale-detection in prompt-rendered active-workers blocks
     * (Eddie's {@code <delegated_workers>}, Arthur's
     * {@code ## Active workers}) and the auto-rebind heuristic on
     * idle timeout. Populated by every parent engine that uses
     * workerLinks.
     */
    private @Nullable Instant lastSeen;

    /**
     * Last seen worker {@link ThinkProcessStatus}. Mirrored from
     * incoming process-events / replies so the active-workers prompt
     * blocks can render "running / blocked / done" beside the
     * triage summary. {@code null} until the first event arrives.
     */
    private @Nullable ThinkProcessStatus workerStatus;

    // === Plan-mirror (eddie-plan-mode.md §2) ===

    /** Last seen worker {@link ProcessMode}. Eddie-only. */
    private @Nullable ProcessMode workerMode;

    /**
     * Snapshot of the worker's Todos at the last {@code todos-updated}
     * frame. Empty list = worker has no plan; {@code null} = no
     * plan-frame received yet. Eddie-only.
     */
    @Builder.Default
    private @Nullable List<TodoItem> workerTodos = new ArrayList<>();

    /**
     * Last seen {@code planVersion} from a {@code plan-proposed} frame.
     * Monotone-increasing per-worker. {@code 0} means no plan was ever
     * proposed. Eddie-only.
     */
    @Builder.Default
    private int planVersion = 0;

    // === Working-memory (eddie-moderator-erweiterung.md §2) ===

    /**
     * Last triage-LLM summary of the worker's recent output — short
     * sentence Eddie uses in the {@code <delegated_workers>} prompt
     * block to keep the thread of conversation across hand-offs.
     * Eddie-only.
     */
    private @Nullable String triageSummary;

    /** Criticality classification of the last triage decision. Eddie-only. */
    private @Nullable Criticality lastCriticality;

    /**
     * Inbox-item id, if the last frame was routed to the inbox via
     * {@code RELAY_INBOX}. Lets Eddie reference „the inbox item I
     * just put there" in subsequent answers. Eddie-only.
     */
    private @Nullable String lastInboxItemId;
}
