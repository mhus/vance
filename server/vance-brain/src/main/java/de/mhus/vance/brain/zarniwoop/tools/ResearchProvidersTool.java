package de.mhus.vance.brain.zarniwoop.tools;

import de.mhus.vance.brain.zarniwoop.SearchProviderFactory;
import de.mhus.vance.brain.zarniwoop.ZarniwoopException;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.ProviderAvailability;
import de.mhus.vance.toolpack.research.QuotaStatus;
import de.mhus.vance.toolpack.research.SearchProviderInstance;
import de.mhus.vance.toolpack.research.SearchScope;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Lists the {@link SearchProviderInstance}s the
 * {@code SearchProviderFactory} has assembled for the calling
 * project, plus availability and (when the instance exposes it)
 * the current quota. Deferred — the LLM activates this only when it
 * wants to inspect the inventory before pinning a specific instance
 * via {@code research_search_expert}.
 */
@Component
@RequiredArgsConstructor
public class ResearchProvidersTool implements Tool {

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of());

    private final SearchProviderFactory factory;

    @Override
    public String name() {
        return "research_providers";
    }

    @Override
    public String description() {
        return "List the search provider instances available in the current "
                + "project. Each entry carries instance id, protocol, the "
                + "modalities it supports, its availability (READY / "
                + "NO_CREDENTIALS / QUOTA_EXHAUSTED / COOLDOWN / DISABLED) "
                + "and — when the protocol exposes it — the current quota. "
                + "Use this when you want to pin a specific instance via "
                + "research_search_expert, or to debug why a search fell "
                + "back to a different provider.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public boolean deferred() {
        return true;
    }

    @Override
    public String searchHint() {
        return "Inspect available search providers and their quota status.";
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
    public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
        if (ctx == null) {
            throw new ToolException("research_providers requires a tool invocation context");
        }
        if (StringUtils.isBlank(ctx.projectId())) {
            throw new ToolException("research tools require a project scope");
        }
        SearchScope scope = new SearchScope(
                ctx.tenantId(), ctx.projectId(), ctx.processId(), ctx.userId());

        List<SearchProviderInstance> instances;
        try {
            instances = factory.assemble(scope);
        } catch (ZarniwoopException e) {
            throw new ToolException(e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>(instances.size());
        for (SearchProviderInstance inst : instances) {
            rows.add(describe(inst, scope));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instances", rows);
        out.put("count", rows.size());
        return out;
    }

    private Map<String, Object> describe(SearchProviderInstance inst, SearchScope scope) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", inst.id());
        row.put("displayName", inst.displayName());
        row.put("modalities", sortedNames(inst.modalities()));
        row.put("domains", sortedNames(inst.domains()));
        row.put("tiers", sortedNames(inst.tiers()));

        ProviderAvailability availability;
        try {
            availability = inst.availability(scope);
        } catch (RuntimeException e) {
            availability = ProviderAvailability.DISABLED;
        }
        row.put("availability", availability.name());

        Optional<QuotaStatus> quota = Optional.empty();
        try {
            quota = inst.currentQuota(scope);
        } catch (RuntimeException ignored) {
            /* instance refused to report — leave quota absent */
        }
        quota.ifPresent(q -> row.put("quota", quotaMap(q)));
        return row;
    }

    private static Map<String, Object> quotaMap(QuotaStatus q) {
        Map<String, Object> qm = new LinkedHashMap<>();
        qm.put("remaining", q.remaining());
        if (q.limit() != null) qm.put("limit", q.limit());
        if (q.resetsAt() != null) qm.put("resetsAt", q.resetsAt().toString());
        if (q.refreshInterval() != null) {
            qm.put("refreshIntervalSeconds", q.refreshInterval().toSeconds());
        }
        return qm;
    }

    private static <E extends Enum<E>> List<String> sortedNames(Set<E> values) {
        if (values == null || values.isEmpty()) return List.of();
        Set<String> names = new TreeSet<>();
        for (E v : values) names.add(v.name());
        return new ArrayList<>(names);
    }
}
