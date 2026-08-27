package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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
     * Lazy to avoid a bean cycle: {@link KitNameResolverService}
     * depends on {@code ThinkEngineService → ToolDispatcher →
     * BuiltInToolSource → ProjectCreateTool → ProjectKitInstaller}.
     * Resolution defers to first call, by which time the singleton
     * graph is complete. The resolver is only consulted on a strict
     * catalog miss, so the lazy lookup costs nothing on the hot path.
     */
    private final ObjectProvider<KitNameResolverService> kitNameResolverProvider;

    /**
     * Look up {@code kitNameOrWish} in the tenant catalog and install
     * the referenced kit into {@code projectId}. Two-stage match:
     *
     * <ol>
     *   <li><b>Strict</b> — the input matches a catalog
     *       {@link ProjectKitEntry#getName()} verbatim. Cheap, no LLM
     *       call. The original behaviour and the hot path for
     *       Web/Foot callers that already picked from {@code kit_list}.</li>
     *   <li><b>Fuzzy</b> — strict miss → {@link KitNameResolverService}
     *       invokes the bundled {@code kit-resolver} Jeltz recipe with
     *       the wish + catalog inline. On MATCH the resolver's pick is
     *       cross-checked against the catalog (closed-vocab guard)
     *       before install. On NONE we throw with a helpful message
     *       that lists the catalog so the caller can re-try.</li>
     * </ol>
     *
     * <p>Null/blank input is a no-op (returns {@code null}) — the
     * caller's "no kit requested" path.
     *
     * @throws KitException when neither stage matches, or when the
     *         underlying install fails (bad source, missing vault
     *         password, etc.)
     */
    public @Nullable KitOperationResultDto installFromCatalog(
            String tenantId, String projectId, @Nullable String kitNameOrWish,
            @Nullable String actor) {
        if (StringUtils.isBlank(kitNameOrWish)) {
            return null;
        }
        ProjectKitEntry entry = catalogService.findByName(tenantId, kitNameOrWish);
        if (entry == null) {
            entry = resolveFuzzy(tenantId, projectId, kitNameOrWish);
        }
        KitImportRequestDto request = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(entry.getSource())
                .mode(KitImportMode.INSTALL)
                .build();
        log.info("Installing catalog kit '{}' into tenantId='{}' projectId='{}' (wish='{}')",
                entry.getName(), tenantId, projectId, kitNameOrWish);
        return kitService.importKit(tenantId, request, actor, SettingWriteOrigin.USER);
    }

    /**
     * Install the kit that {@code sourceProjectId} is the source of into
     * {@code projectId}.
     *
     * <p>Beside {@link #installFromCatalog} rather than inside it: that method
     * resolves a <em>catalog key</em> and falls through to an LLM when the key
     * misses, which is exactly the wrong behaviour for a project name — a typo
     * would install the closest-looking catalog kit instead of failing. Here
     * the name is passed straight through as a {@code project:} url and the
     * loader decides, including whether the caller may read that project.
     *
     * <p>Null/blank is the caller's "no kit requested" path, as above.
     */
    public @Nullable KitOperationResultDto installFromProject(
            String tenantId, String projectId, @Nullable String sourceProjectId,
            @Nullable String actor) {
        if (StringUtils.isBlank(sourceProjectId)) {
            return null;
        }
        KitImportRequestDto request = KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitInheritDto.builder()
                        .url(KitSourceType.PROJECT_SCHEME + sourceProjectId.trim())
                        .build())
                .mode(KitImportMode.INSTALL)
                .build();
        log.info("Installing kit from project '{}' into tenantId='{}' projectId='{}'",
                sourceProjectId, tenantId, projectId);
        return kitService.importKit(tenantId, request, actor, SettingWriteOrigin.USER);
    }

    /**
     * Fall through to the LLM-backed kit-resolver. Throws
     * {@link KitException} with a catalog listing + the resolver's
     * rationale when nothing matches — the caller surface (Eddie /
     * REST / Foot) reports it back so the user can retry with a
     * recognisable wish.
     */
    private ProjectKitEntry resolveFuzzy(
            String tenantId, String projectId, String wish) {
        KitNameResolverService resolver = kitNameResolverProvider.getObject();
        KitNameResolverService.Result result = resolver.resolve(tenantId, projectId, wish);
        if (result.matched() && result.kitName() != null) {
            ProjectKitEntry entry = catalogService.findByName(tenantId, result.kitName());
            if (entry != null) {
                log.info("KitInstaller: resolver matched wish='{}' → kit='{}' rationale='{}'",
                        wish, entry.getName(), result.rationale());
                return entry;
            }
            // Defence-in-depth: resolver lied past its closed vocab.
            log.warn("KitInstaller: resolver picked '{}' but catalog has no entry by that name",
                    result.kitName());
        }
        throw new KitException(
                "Kit '" + wish + "' did not match any catalog entry. "
                        + result.rationale()
                        + " Available kits: " + listCatalogNames(tenantId)
                        + ". Pass one of these as `kitName`, "
                        + "or use kit_install with a git/file URL to bring in "
                        + "a kit that isn't in the catalog yet.");
    }

    private String listCatalogNames(String tenantId) {
        ProjectKitsCatalogDto catalog = catalogService.load(tenantId);
        if (catalog == null || catalog.getKits() == null || catalog.getKits().isEmpty()) {
            return "(catalog is empty)";
        }
        return catalog.getKits().stream()
                .map(ProjectKitEntry::getName)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(", "));
    }
}
