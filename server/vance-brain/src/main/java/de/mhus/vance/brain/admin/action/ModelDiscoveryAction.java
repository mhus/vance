package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionItemDto;
import de.mhus.vance.api.admin.AdminActionRunResultDto;
import de.mhus.vance.brain.ai.discovery.ModelDiscoveryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code model-discovery} — run one auto-discovery pass over the
 * tenant: walk every project's {@code ai.provider.<instance>.*}
 * credentials, call each backend's listing endpoint, and write the
 * models found under {@code _vance/model-auto/}. Migrated from the
 * profile actions.
 *
 * <p><b>Tenant-only, by design:</b> discovery walks the tenant's whole
 * scope-tree ({@code discoverForTenant}) — cross-scope selection is
 * deliberately not exposed by the service, and a project parameter
 * would silently mean something different from what it says.
 */
@Component
@RequiredArgsConstructor
public class ModelDiscoveryAction implements AdminAction {

    private final ModelDiscoveryService discoveryService;

    @Override
    public String id() {
        return "model-discovery";
    }

    @Override
    public Map<String, String> title() {
        return Map.of(
                "de", "Modelle entdecken",
                "en", "Discover models");
    }

    @Override
    public Map<String, String> description() {
        return Map.of(
                "de",
                        "Ein Auto-Discovery-Lauf über den Tenant: liest die Provider-Credentials "
                                + "jedes Projekts, fragt die Listing-Endpoints ab und schreibt gefundene "
                                + "Modelle nach _vance/model-auto/. Der Katalog wird danach automatisch "
                                + "neu geladen.",
                "en",
                        "One auto-discovery pass over the tenant: reads every project's provider "
                                + "credentials, calls the listing endpoints and writes found models to "
                                + "_vance/model-auto/. The catalog reloads automatically afterwards.");
    }

    @Override
    public AdminActionScope scope() {
        return AdminActionScope.TENANT_ONLY;
    }

    @Override
    public AdminActionRunResultDto run(AdminActionContext context) {
        ModelDiscoveryService.DiscoveryResult result = discoveryService.discoverForTenant(context.tenantId());
        List<AdminActionItemDto> items = new ArrayList<>();
        result.skippedInstances()
                .forEach((key, reason) -> items.add(AdminActionItemDto.builder()
                        .key(key)
                        .ok(false)
                        .detail(reason)
                        .build()));
        boolean ok = result.modelsFailed() == 0;
        return AdminActionRunResultDto.builder()
                .actionId(id())
                .ok(ok)
                .summary("Discovery finished: %d models written, %d failed, %d instances "
                        + "scanned in %d scopes (%d ms)"
                                .formatted(
                                        result.modelsWritten(),
                                        result.modelsFailed(),
                                        result.instancesScanned(),
                                        result.scopesScanned(),
                                        result.durationMs()))
                .items(items)
                .build();
    }
}
