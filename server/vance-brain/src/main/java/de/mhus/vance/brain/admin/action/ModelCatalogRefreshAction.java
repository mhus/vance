package de.mhus.vance.brain.admin.action;

import de.mhus.vance.api.admin.AdminActionRunResultDto;
import de.mhus.vance.brain.ai.ModelCatalog;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code model-catalog-refresh} — force an immediate reload of the
 * model catalog. The scheduled refresh runs every 30 minutes; this
 * action exists so operators see a freshly edited
 * {@code _vance/model/…} document without waiting for the timer.
 * Migrated from the profile actions, where it had no home.
 *
 * <p><b>Tenant-only, by nature:</b> the catalog cache is global to
 * the brain pod — a project parameter would not change what the
 * action does, so it does not pretend to accept one.
 */
@Component
@RequiredArgsConstructor
public class ModelCatalogRefreshAction implements AdminAction {

    private final ModelCatalog modelCatalog;

    @Override
    public String id() {
        return "model-catalog-refresh";
    }

    @Override
    public Map<String, String> title() {
        return Map.of(
                "de", "Modell-Katalog neu laden",
                "en", "Reload model catalog");
    }

    @Override
    public Map<String, String> description() {
        return Map.of(
                "de",
                        "Erzwingt einen sofortigen Reload des Modell-Katalogs statt auf den "
                                + "30-Minuten-Timer zu warten — nach dem Editieren von "
                                + "_vance/model/-Dokumenten.",
                "en",
                        "Forces an immediate model catalog reload instead of waiting for the "
                                + "30-minute timer — after editing _vance/model/ documents.");
    }

    @Override
    public AdminActionScope scope() {
        return AdminActionScope.TENANT_ONLY;
    }

    @Override
    public AdminActionRunResultDto run(AdminActionContext context) {
        ModelCatalog.RefreshResult result = modelCatalog.refresh();
        return AdminActionRunResultDto.builder()
                .actionId(id())
                .ok(true)
                .summary("Catalog reloaded: %d bundled models, %d bundled providers, "
                        + "%d override scopes in %d ms"
                                .formatted(
                                        result.bundledModelsLoaded(),
                                        result.bundledProvidersLoaded(),
                                        result.overrideScopes(),
                                        result.durationMs()))
                .build();
    }
}
