package de.mhus.vance.shared.llmusage;

import de.mhus.vance.shared.thinkprocess.ThinkProcessDocument;
import org.jspecify.annotations.Nullable;

/**
 * Who a model call is charged to. One object, carried unopened from the
 * caller through the AI layer into the ledger write.
 *
 * <p>Lives in {@code vance-shared} on purpose: {@link LlmUsageService}
 * has to read it, and if the record lived in {@code brain.ai} it would be
 * torn into loose fields at the module boundary — which is exactly where
 * the divergences appeared before (one writer set {@code providerType},
 * the other passed {@code null}).
 *
 * <p><b>Not the same thing as the catalog scope.</b>
 * {@code AiChatOptions.tenantId/projectId} tell {@link
 * de.mhus.vance.shared.settings.SettingService}-backed model lookups
 * <i>which layer to resolve against</i>, and a tenant-pinned process
 * deliberately leaves {@code projectId} unset there so endpoint and
 * catalog are read from the same layer. Billing must still say which
 * project burned the tokens. Two questions, two fields — do not merge
 * them.
 *
 * @param tenantId   always set; a call that cannot name a tenant is not
 *                   billable and must not reach the ledger
 * @param projectId  project the work belongs to, {@code null} for
 *                   tenant-scoped flows
 * @param sessionId  {@code null} outside a chat session
 * @param processId  {@code null} for process-less calls
 * @param caller     who issued the call — a think-engine name
 *                   ({@code arthur}, {@code jeltz}), {@link
 *                   LlmUsageService#CALLER_LIGHT} for single-shot light
 *                   calls, or another {@code _}-prefixed subsystem.
 *                   Deliberately not called {@code source}: in this tree
 *                   that word means a foreign inbound source (Zarniwoop,
 *                   Centauri, Jaglan), which is the opposite direction
 * @param recipeName recipe in effect, {@code null} when none applies
 */
public record CallAttribution(
        String tenantId,
        @Nullable String projectId,
        @Nullable String sessionId,
        @Nullable String processId,
        String caller,
        @Nullable String recipeName) {

    public CallAttribution {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("CallAttribution.tenantId is required");
        }
        if (caller == null || caller.isBlank()) {
            throw new IllegalArgumentException("CallAttribution.caller is required");
        }
    }

    /** Attribution for a call issued by a think-engine on behalf of {@code process}. */
    public static CallAttribution ofProcess(ThinkProcessDocument process, String caller) {
        return new CallAttribution(
                process.getTenantId(),
                process.getProjectId(),
                process.getSessionId(),
                process.getId(),
                caller,
                process.getRecipeName());
    }

    /**
     * Attribution for a single-shot call that no think-engine issued —
     * discovery, follow-up, title generation, triage. The recipe carries
     * the concrete caller, so per-recipe reports stay meaningful even
     * though every row says {@code _light}.
     */
    public static CallAttribution light(
            String tenantId,
            @Nullable String projectId,
            @Nullable String processId,
            String recipeName) {
        return new CallAttribution(
                tenantId, projectId, null, processId,
                LlmUsageService.CALLER_LIGHT, recipeName);
    }

    /**
     * Attribution for an internal service call outside any process —
     * {@code caller} names the subsystem ({@code _fenchurch}, {@code _rag}).
     */
    public static CallAttribution ofService(
            String tenantId, @Nullable String projectId, String caller) {
        return new CallAttribution(tenantId, projectId, null, null, caller, null);
    }
}
