package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Installs a catalog-listed kit into a freshly-created project. The
 * three callers (REST {@code POST /admin/projects}, Eddie's
 * {@code project_create} tool, Foot's {@code /project-create} slash
 * command) all funnel through here so the catalog-lookup and
 * install-pipeline are consistent.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §6.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectKitInstaller {

    private final ProjectKitsCatalogService catalogService;
    private final KitService kitService;

    /**
     * Look up {@code kitName} in the tenant catalog and install the
     * referenced kit into {@code projectId}. Returns the
     * {@link KitOperationResultDto} from the underlying
     * {@link KitService#importKit}. Null/blank {@code kitName} is a
     * no-op (returns {@code null}).
     *
     * @throws KitException when {@code kitName} is not in the catalog,
     *         or when the underlying install fails (bad source, missing
     *         vault password, etc.)
     */
    public @Nullable KitOperationResultDto installFromCatalog(
            String tenantId, String projectId, @Nullable String kitName,
            @Nullable String actor) {
        if (StringUtils.isBlank(kitName)) {
            return null;
        }
        ProjectKitEntry entry = catalogService.findByName(tenantId, kitName);
        if (entry == null) {
            throw new KitException(
                    "kit '" + kitName + "' is not in tenant '" + tenantId + "' catalog");
        }
        KitImportRequestDto request = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(entry.getSource())
                .mode(KitImportMode.INSTALL)
                .build();
        log.info("Installing catalog kit '{}' into tenantId='{}' projectId='{}'",
                kitName, tenantId, projectId);
        return kitService.importKit(tenantId, request, actor);
    }
}
