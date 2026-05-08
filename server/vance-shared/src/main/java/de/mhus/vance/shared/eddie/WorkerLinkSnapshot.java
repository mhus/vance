package de.mhus.vance.shared.eddie;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.mhus.vance.api.eddie.ChannelMode;
import de.mhus.vance.api.inbox.Criticality;
import de.mhus.vance.api.thinkprocess.ProcessMode;
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
 * Eddie's per-worker mirror, embedded as an array on her own
 * {@code ThinkProcessDocument.workerLinks}. One entry per active
 * worker-process Eddie is connected to (one Working WS each — see
 * {@code specification/eddie-engine.md} §8.1).
 *
 * <p>Two consumers share this struct:
 * <ul>
 *   <li><b>Triage / Working-Memory</b>
 *       ({@code planning/eddie-moderator-erweiterung.md}) — owns the
 *       {@code triageSummary} / {@code lastCriticality} /
 *       {@code lastInboxItemId} fields.</li>
 *   <li><b>Plan-Mirror + Fusion</b>
 *       ({@code planning/eddie-plan-mode.md}) — owns the
 *       {@code workerMode} / {@code workerTodos} / {@code planVersion}
 *       fields.</li>
 * </ul>
 *
 * <p>The connection-identity fields ({@code workerProcessId},
 * {@code workerSessionId}, {@code workerPodAddress}) are the input the
 * {@code EddieEngine} bean uses to (re)build the in-memory
 * {@code WebSocketClient} pool after pod reassignment — they survive
 * suspend/resume because the snapshot itself lives in Mongo.
 *
 * <p>{@code workerProcessId} is the upsert key. Calls to
 * {@code ThinkProcessService.upsertWorkerLink} merge by this id; new
 * field values overwrite old ones, missing fields stay untouched is
 * not supported — pass a complete snapshot.
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
     * Worker {@code ProjectDocument.name} — for ID-prefix in the
     * fusion-rendered plan ({@code <engine>-<projectName>/<localId>}).
     */
    private String workerProjectName = "";

    /** Worker session id — for reconnect to the Working WS. */
    private String workerSessionId = "";

    /**
     * Address of the worker-project's home pod ({@code ipv4:port} per
     * memory note). Used by {@code EngineWsClient} to (re)open the
     * Working WS without re-querying the project manager.
     */
    private String workerPodAddress = "";

    @Builder.Default
    private ChannelMode channelMode = ChannelMode.MILESTONES;

    /**
     * Last time Eddie saw a frame from this worker. Drives stale-
     * detection in the {@code <delegated_workers>} system-prompt block
     * and the auto-rebind heuristic on idle timeout.
     */
    private @Nullable Instant lastSeen;

    // === Plan-mirror (eddie-plan-mode.md §2) ===

    /** Last seen worker {@link ProcessMode}. */
    private @Nullable ProcessMode workerMode;

    /**
     * Snapshot of the worker's Todos at the last {@code todos-updated}
     * frame. Empty list = worker has no plan; {@code null} = no
     * plan-frame received yet.
     */
    @Builder.Default
    private @Nullable List<TodoItem> workerTodos = new ArrayList<>();

    /**
     * Last seen {@code planVersion} from a {@code plan-proposed} frame.
     * Monotone-increasing per-worker. {@code 0} means no plan was ever
     * proposed.
     */
    @Builder.Default
    private int planVersion = 0;

    // === Working-memory (eddie-moderator-erweiterung.md §2) ===

    /**
     * Last triage-LLM summary of the worker's recent output — short
     * sentence Eddie uses in the {@code <delegated_workers>} prompt
     * block to keep the thread of conversation across hand-offs.
     */
    private @Nullable String triageSummary;

    /** Criticality classification of the last triage decision. */
    private @Nullable Criticality lastCriticality;

    /**
     * Inbox-item id, if the last frame was routed to the inbox via
     * {@code RELAY_INBOX}. Lets Eddie reference „the inbox item I
     * just put there" in subsequent answers.
     */
    private @Nullable String lastInboxItemId;
}
