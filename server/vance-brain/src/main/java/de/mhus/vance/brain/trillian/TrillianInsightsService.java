package de.mhus.vance.brain.trillian;

import de.mhus.vance.api.insights.TrillianControlInsightsDto;
import de.mhus.vance.api.insights.TrillianInsightsDto;
import de.mhus.vance.api.insights.TrillianPendingEntryInsightsDto;
import de.mhus.vance.api.insights.TrillianTaskWorkerInsightsDto;
import de.mhus.vance.api.insights.TrillianWorkerInsightsDto;
import de.mhus.vance.api.session.SessionStatus;
import de.mhus.vance.api.thinkprocess.ThinkProcessStatus;
import de.mhus.vance.shared.session.SessionDocument;
import de.mhus.vance.shared.session.SessionService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.shared.thinkprocess.ThinkProcessService;
import de.mhus.vance.shared.user.UserDocument;
import de.mhus.vance.shared.user.UserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the Trillian state image for the insights inspector: one entry
 * per control process, pairing it with its user-loop, its task workers and
 * its pending inbox.
 *
 * <p>This is the read twin of {@code //trillian info} — same sources
 * ({@link TrillianInternalApi} for the loop's runtime state), typed as
 * wire DTOs instead of rendered maps. The command keeps working when the
 * web UI is not open; this is what the web UI sees.
 *
 * <p>Closed control processes are left out: a closed Trillian's account
 * is gone with the session, and its state view would answer with three
 * {@code null} sides.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrillianInsightsService {

    /** Sanity cap — a tenant runs a handful of Trillians, not thousands. */
    private static final int LIMIT = 200;

    private final ThinkProcessService thinkProcessService;
    private final SessionService sessionService;
    private final UserService userService;
    private final TrillianInternalApi api;
    private final TrillianActivationGate activationGate;

    /** Every live Trillian pair of the tenant, newest control process first. */
    public List<TrillianInsightsDto> listAll(String tenantId) {
        List<TrillianInsightsDto> out = new ArrayList<>();
        for (ThinkProcessDocument control : thinkProcessService.findByTenantAndEngine(
                tenantId, TrillianSessionBootstrapper.CONTROL_ENGINE_NAME, LIMIT)) {
            if (control.getStatus() == ThinkProcessStatus.CLOSED) {
                continue;
            }
            out.add(describe(control));
        }
        return out;
    }

    /**
     * State of one pair, addressed by the control process's Mongo id —
     * the addressing key the pause/resume endpoints use too.
     */
    public Optional<TrillianInsightsDto> describe(String tenantId, String controlProcessId) {
        return thinkProcessService
                .findById(controlProcessId)
                .filter(c -> tenantId.equals(c.getTenantId()))
                .filter(c -> TrillianSessionBootstrapper.CONTROL_ENGINE_NAME.equals(c.getThinkEngine()))
                .map(this::describe);
    }

    private TrillianInsightsDto describe(ThinkProcessDocument control) {
        Optional<ThinkProcessDocument> peer = api.findPeer(control.getId());
        TrillianWorkerInsightsDto worker = peer.map(this::toWorkerDto).orElse(null);
        String accountName = worker == null ? null : worker.getAccountId();

        List<TrillianTaskWorkerInsightsDto> taskWorkers = new ArrayList<>();
        List<TrillianPendingEntryInsightsDto> pending = new ArrayList<>();
        if (peer.isPresent()) {
            for (TrillianInternalApi.TaskWorkerSnapshot t : api.listTaskWorkers(peer.get())) {
                taskWorkers.add(TrillianTaskWorkerInsightsDto.builder()
                        .name(t.name())
                        .processId(t.processId())
                        .projectId(t.projectId())
                        .status(t.status())
                        .engine(t.engine())
                        .createdAt(t.createdAt())
                        .build());
            }
            for (TrillianInternalApi.PendingEntry e : api.listPending(peer.get().getId())) {
                pending.add(TrillianPendingEntryInsightsDto.builder()
                        .kind(e.taskEvent() == null ? "message" : e.taskEvent())
                        .taskId(e.taskId())
                        .description(e.description())
                        .queuedAt(e.queuedAt())
                        .build());
            }
        }

        return TrillianInsightsDto.builder()
                .control(TrillianControlInsightsDto.builder()
                        .sessionId(control.getSessionId())
                        .status(sessionStatusOf(control))
                        .projectId(control.getProjectId())
                        .processId(control.getId())
                        .processName(control.getName())
                        .processStatus(control.getStatus())
                        .nature(param(control, TrillianSessionBootstrapper.PARAM_NATURE))
                        .createdAt(control.getCreatedAt())
                        .build())
                .worker(worker)
                // The same cascade the bootstrap gate walks, so the view
                // answers "would a new loop start?" with the gate's own
                // vocabulary — hub layer keyed by the account when one exists.
                .loopsEnabled(activationGate.loopsEnabled(control.getTenantId(), accountName, control.getProjectId()))
                .taskWorkers(taskWorkers)
                .pending(pending)
                .build();
    }

    private TrillianWorkerInsightsDto toWorkerDto(ThinkProcessDocument peer) {
        TrillianInternalApi.PeerStateSnapshot snap = api.snapshotPeerState(peer);
        String account = param(peer, TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME);
        Map<String, String> attributes = new LinkedHashMap<>();
        TrillianInternalApi.readAttributes(peer).forEach((k, v) -> attributes.put(k, String.valueOf(v)));
        return TrillianWorkerInsightsDto.builder()
                .accountId(account)
                .accountTitle(accountTitleOf(peer.getTenantId(), account))
                .sessionId(peer.getSessionId())
                .processId(snap.processId())
                .processName(snap.name())
                .status(snap.status())
                .pendingInbox(snap.pendingInboxCount())
                .attributes(attributes)
                .build();
    }

    /**
     * The account's display name, or {@code null} when the account is gone.
     * Cosmetic: the state view must still answer.
     */
    private @Nullable String accountTitleOf(String tenantId, @Nullable String account) {
        if (account == null) {
            return null;
        }
        try {
            return userService
                    .findByTenantAndName(tenantId, account)
                    .map(UserDocument::getTitle)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Trillian insights: could not read title of '{}': {}", account, e.toString());
            return null;
        }
    }

    private @Nullable SessionStatus sessionStatusOf(ThinkProcessDocument control) {
        return sessionService
                .findBySessionId(control.getSessionId())
                .map(SessionDocument::getStatus)
                .orElse(null);
    }

    private static @Nullable String param(ThinkProcessDocument process, String key) {
        Map<String, Object> params = process.getEngineParams();
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
