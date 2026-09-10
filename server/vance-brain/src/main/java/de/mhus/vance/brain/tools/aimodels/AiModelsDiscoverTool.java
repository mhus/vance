package de.mhus.vance.brain.tools.aimodels;

import de.mhus.vance.brain.ai.discovery.ModelDiscoveryService;
import de.mhus.vance.brain.permission.SecurityContextFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.PermissionService;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs AI-model auto-discovery for the calling tenant — the tool twin of
 * {@code POST /brain/{tenant}/admin/ai-models/discover}. Walks every
 * project's {@code ai.provider.<instance>.*} settings, calls each
 * provider's listing endpoint, writes one YAML doc per discovered model
 * under {@code _vance/model-auto/<instance>/<slug>.yaml} in the project
 * where the credentials live (symmetry rule), then refreshes the
 * in-memory {@code ModelCatalog} so the new docs are visible at once.
 *
 * <p>Primarily the creator worker's completion step after registering a
 * provider instance (sidecar + settings): the operator historically had
 * to run the Profile → Actions button themselves because no LLM-callable
 * surface existed. The button stays; this tool removes the forced detour
 * for the chat-driven setup path.
 *
 * <p>Authorization mirrors the REST controller: ADMIN on the tenant.
 * The dispatcher's generic EXECUTE check only covers the caller's own
 * process scope, but discovery uses the tenant's credentials across
 * every project and writes into projects the caller is not in — a
 * non-admin user must not be able to trigger that through an agent.
 * A headless caller (null userId → SYSTEM subject) passes, as anywhere
 * else in the tool path.
 *
 * <p>Deferred by design ({@code primary() == false}): scheduling and
 * provider maintenance is opt-in — the creator recipe promotes it via
 * the {@code @aimodels} label selector, default chats keep it out of
 * their primary manifest and delegate to the creator.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiModelsDiscoverTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final ModelDiscoveryService discoveryService;
    private final PermissionService permissionService;
    private final SecurityContextFactory contextFactory;

    @Override
    public String name() {
        return "ai_models_discover";
    }

    @Override
    public String description() {
        return "Run AI-model auto-discovery for this tenant: walks every project's "
                + "ai.provider.<instance>.* settings, calls each configured "
                + "provider's model-listing endpoint, and writes one YAML doc "
                + "per discovered model under _vance/model-auto/<instance>/ "
                + "in the project where the credentials live. Refreshes the "
                + "model catalog afterwards, so freshly written manual docs "
                + "under _vance/model/ become visible in the same call. "
                + "Auto-docs never carry pricing or kind — those need a "
                + "manual doc. Requires tenant ADMIN. Use after setting up a "
                + "new provider instance (sidecar + apiKey/baseUrl settings) "
                + "to populate its model list without hand-writing docs.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Set<String> labels() {
        return Set.of("admin", "aimodels");
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        permissionService.enforce(
                contextFactory.forToolSubject(ctx.tenantId(), ctx.userId()),
                new Resource.Tenant(ctx.tenantId()),
                Action.ADMIN);
        log.info("AI-model discovery requested via tool by tenant='{}' user='{}'", ctx.tenantId(), ctx.userId());
        try {
            ModelDiscoveryService.DiscoveryResult result = discoveryService.discoverForTenant(ctx.tenantId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenantId", result.tenantId());
            out.put("scopesScanned", result.scopesScanned());
            out.put("instancesScanned", result.instancesScanned());
            out.put("modelsWritten", result.modelsWritten());
            out.put("modelsFailed", result.modelsFailed());
            out.put("pricingDocsCreated", result.pricingDocsCreated());
            out.put("pricingDocsUpdated", result.pricingDocsUpdated());
            out.put("skippedInstances", result.skippedInstances());
            out.put("durationMs", result.durationMs());
            out.put(
                    "note",
                    "Auto-docs live under _vance/model-auto/ and are "
                            + "overwritten by every discovery run. Prices the endpoint "
                            + "reports land in _vance/model/ as auto: true files "
                            + "(created when absent, refreshed while the marker stays, "
                            + "untouched once an operator removes it). Kind and "
                            + "capabilities belong in manual docs under _vance/model/.");
            return out;
        } catch (RuntimeException e) {
            throw new ToolException("AI-model discovery failed: " + e.getMessage(), e);
        }
    }
}
