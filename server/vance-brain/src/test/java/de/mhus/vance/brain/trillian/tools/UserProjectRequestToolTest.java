package de.mhus.vance.brain.trillian.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.roles.ProjectRole;
import de.mhus.vance.api.roles.RoleSubjectType;
import de.mhus.vance.brain.roles.RoleRequestSupport;
import de.mhus.vance.brain.trillian.TrillianControlEngine;
import de.mhus.vance.brain.trillian.TrillianInternalApi;
import de.mhus.vance.brain.trillian.TrillianSessionBootstrapper;
import de.mhus.vance.shared.role.request.RoleRequestOperation;
import de.mhus.vance.shared.role.request.RoleRequestService;
import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The tool exists to keep a generated throwaway account name out of the
 * human's way — while still granting nothing on its own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProjectRequestToolTest {

    private static final String TENANT = "acme";
    private static final String WORKER = "_trillian-04506";

    @Mock
    TrillianInternalApi api;

    @Mock
    RoleRequestSupport requests;

    @Mock
    ToolInvocationContext ctx;

    UserProjectRequestTool tool;

    @BeforeEach
    void setUp() {
        tool = new UserProjectRequestTool(api, requests);
        when(ctx.tenantId()).thenReturn(TENANT);
        when(ctx.userId()).thenReturn("road.runner");
        when(ctx.processId()).thenReturn("control-proc");
        when(api.findPeer("control-proc")).thenReturn(Optional.of(peerWith(WORKER)));
        when(requests.raise(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RoleRequestSupport.Receipt("req-1", "item-1", "PENDING", "marvin.acme", false));
    }

    @Test
    void request_fillsInTheWorkerAccountItself() {
        tool.invoke(params("test1", "worker needs to count documents there"), ctx);

        // The whole point: the human never has to know or type the
        // generated account name.
        verify(requests)
                .raise(
                        TENANT,
                        "road.runner",
                        "control-proc",
                        null,
                        RoleRequestOperation.GRANT,
                        RoleRequestService.Target.projectGrant(
                                "test1", RoleSubjectType.USER, WORKER, ProjectRole.MEMBER),
                        "worker needs to count documents there");
    }

    @Test
    void request_reportsPendingNotGranted() {
        Map<String, Object> out = tool.invoke(params("test1", null), ctx);

        assertThat(out)
                .containsEntry("granted", false)
                .containsEntry("status", "PENDING")
                .containsEntry("role", "MEMBER")
                .containsEntry("awaitingApprovalBy", "marvin.acme");
    }

    @Test
    void request_asksForMemberNotAdmin() {
        Map<String, Object> out = tool.invoke(params("test1", null), ctx);

        // A worker let into a foreign project works there; it does not
        // administer it.
        assertThat(out).containsEntry("role", "MEMBER");
    }

    @Test
    void toolIsGatedOnTheControlRole() {
        // The worker loop must not be able to widen its own reach — only
        // the side the human talks to may ask.
        assertThat(tool.requiresEngineRoles()).containsExactly(TrillianControlEngine.ROLE_TRILLIAN_CONTROL);
    }

    @Test
    void missingProjectId_isRejected() {
        assertThatThrownBy(() -> tool.invoke(params(null, "because"), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("projectId");
    }

    @Test
    void withoutAPairedWorker_theToolRefuses() {
        when(api.findPeer("control-proc")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.invoke(params("test1", null), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("Trillian-Control session");
    }

    @Test
    void workerWithoutBoundAccount_isRefusedRatherThanGuessed() {
        when(api.findPeer("control-proc")).thenReturn(Optional.of(peerWith(null)));

        assertThatThrownBy(() -> tool.invoke(params("test1", null), ctx))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("no bound service account");
    }

    @Test
    void reusedRequest_isReportedAsAlreadyPending() {
        when(requests.raise(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RoleRequestSupport.Receipt("req-1", "item-1", "PENDING", null, true));

        Map<String, Object> out = tool.invoke(params("test1", null), ctx);

        assertThat((String) out.get("note")).contains("already awaiting approval");
    }

    @Test
    void noDecider_saysTheRequestWillExpire() {
        when(requests.raise(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new RoleRequestSupport.Receipt("req-1", null, "PENDING", null, false));

        Map<String, Object> out = tool.invoke(params("test1", null), ctx);

        assertThat((String) out.get("note")).contains("No administrator");
        assertThat(out).doesNotContainKey("itemId");
    }

    // ──── helpers ───────────────────────────────────────────────────────

    private static Map<String, Object> params(String projectId, String reason) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (projectId != null) params.put("projectId", projectId);
        if (reason != null) params.put("reason", reason);
        return params;
    }

    private static ThinkProcessDocument peerWith(String workerName) {
        ThinkProcessDocument peer = new ThinkProcessDocument();
        peer.setId("user-loop");
        Map<String, Object> engineParams = new LinkedHashMap<>();
        if (workerName != null) {
            engineParams.put(TrillianSessionBootstrapper.PARAM_TRILLIAN_USER_NAME, workerName);
        }
        peer.setEngineParams(engineParams);
        return peer;
    }
}
