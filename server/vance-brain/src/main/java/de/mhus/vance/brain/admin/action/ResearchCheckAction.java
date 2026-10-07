package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionRunResultDto;
import de.mhus.vance.brain.zarniwoop.ZarniwoopResearchService;
import de.mhus.vance.toolpack.ToolInvocationContext;
import de.mhus.vance.toolpack.research.RankedHitSet;
import de.mhus.vance.toolpack.research.SearchScope;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@code research-check} — verifies that research works in the target
 * scope: one trivial investigation through the same dispatcher the
 * research tools use ({@code ZarniwoopResearchService.investigate}),
 * with a question so small it can only fail for infrastructure
 * reasons — missing credentials, a dead endpoint, no sources
 * configured. The hit count is the honest outcome: a scope with no
 * research sources configured answers zero hits, which is a finding
 * about configuration, not about the weather.
 *
 * <p><b>Tenant scope runs against {@code _tenant}:</b> research is
 * project-scoped by contract (sources live in a project), so the
 * tenant scope checks the {@code _tenant} project — the same layer a
 * new project would inherit from.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ResearchCheckAction implements AdminAction {

    /** Small, provider-neutral, and answerable by any live search backend. */
    private static final String CHECK_QUESTION = "What is the current version of Python?";

    private final ZarniwoopResearchService researchService;

    @Override
    public String id() {
        return "research-check";
    }

    @Override
    public Map<String, String> title() {
        return Map.of(
                "de", "Research prüfen",
                "en", "Check research");
    }

    @Override
    public Map<String, String> description() {
        return Map.of(
                "de",
                        "Führt eine minimale Untersuchung über denselben Dispatcher aus wie die "
                                + "Research-Tools — mit einer Frage, die nur aus Infrastruktur-Gründen "
                                + "scheitern kann (Credentials, Endpoint, fehlende Quellen). Der "
                                + "Mandant prüft dabei das _tenant-Projekt.",
                "en",
                        "Runs one minimal investigation through the same dispatcher the research "
                                + "tools use — with a question that can only fail for infrastructure "
                                + "reasons (credentials, endpoint, missing sources). In the tenant "
                                + "scope this checks the _tenant project.");
    }

    @Override
    public AdminActionScope scope() {
        return AdminActionScope.TENANT_AND_PROJECT;
    }

    @Override
    public AdminActionRunResultDto run(AdminActionContext context) {
        long start = System.currentTimeMillis();
        String projectId = context.effectiveProjectId();
        try {
            ToolInvocationContext ctx =
                    new ToolInvocationContext(context.tenantId(), projectId, null, null, context.userId());
            RankedHitSet result = researchService.investigate(
                    CHECK_QUESTION, new SearchScope(context.tenantId(), projectId, null, context.userId()), ctx);
            long ms = System.currentTimeMillis() - start;
            int hits = result.keptHits().size();
            boolean ok = hits > 0;
            return AdminActionRunResultDto.builder()
                    .actionId(id())
                    .ok(ok)
                    .summary(
                            ok
                                    ? "Research works: %d hits from %d source(s) in %d ms"
                                            .formatted(
                                                    hits, result.instancesUsed().size(), ms)
                                    : "Research returned no hits — check that research sources are "
                                            + "configured and enabled in this scope")
                    .build();
        } catch (RuntimeException e) {
            log.info("ResearchCheck: investigation failed: {}", e.getMessage());
            return AdminActionRunResultDto.builder()
                    .actionId(id())
                    .ok(false)
                    .summary("Research failed: " + e.getMessage())
                    .build();
        }
    }
}
