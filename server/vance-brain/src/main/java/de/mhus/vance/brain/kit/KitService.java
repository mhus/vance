package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.shared.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Public entry point for the kit subsystem. Wraps the
 * loader/resolver/installer/exporter chain in clean
 * {@code install/update/apply/export/status} verbs.
 *
 * <p>Every operation is project-scoped — the caller passes the target
 * project explicitly. Whether that's a regular project, the
 * tenant-wide {@code _vance} project, or a per-user
 * {@code _user_<id>} project does not matter to the service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitService {

    private final KitResolver resolver;
    private final KitInstaller installer;
    private final KitExporter exporter;
    private final KitWorkspace workspace;
    private final ProjectService projectService;

    /**
     * Install / update / apply a kit. The {@code mode} on the request
     * selects the variant. {@code INSTALL} requires no active manifest;
     * {@code UPDATE} requires one; {@code APPLY} ignores the manifest.
     */
    public KitOperationResultDto importKit(
            String tenantId, KitImportRequestDto request, @Nullable String actor) {
        validateImport(request);
        // The kit installer writes documents/settings/tools under
        // request.projectId. If no ProjectDocument with that name
        // exists, downstream Eddie/Ford tools that go through
        // EddieContext.resolveProject would fail with "project not
        // found in tenant". Reject the install up front rather than
        // leaving the project in an inconsistent state.
        if (projectService.findByTenantAndName(tenantId, request.getProjectId()).isEmpty()) {
            throw new KitException("project '" + request.getProjectId()
                    + "' does not exist in tenant '" + tenantId
                    + "' — create it before installing a kit");
        }
        if (request.getMode() == KitImportMode.INSTALL || request.getMode() == KitImportMode.UPDATE) {
            KitManifestDto current = installer.loadManifest(tenantId, request.getProjectId());
            if (request.getMode() == KitImportMode.INSTALL && current != null) {
                throw new KitException("project " + request.getProjectId()
                        + " already has an active kit '" + current.getKit().getName()
                        + "' — use update or uninstall first");
            }
            if (request.getMode() == KitImportMode.UPDATE && current == null) {
                throw new KitException("project " + request.getProjectId()
                        + " has no active kit — install first");
            }
        }
        KitResolver.ResolvedKit resolved = null;
        try {
            resolved = resolver.resolve(request.getSource(), request.getToken());
            return installer.apply(
                    tenantId,
                    request.getProjectId(),
                    request.getSource(),
                    resolved,
                    request.getMode(),
                    request.isPrune(),
                    request.isKeepPasswords(),
                    request.getVaultPassword(),
                    actor);
        } finally {
            if (resolved != null) resolved.cleanup(workspace);
        }
    }

    /** Convenience wrapper: forces {@link KitImportMode#INSTALL}. */
    public KitOperationResultDto install(
            String tenantId, KitImportRequestDto request, @Nullable String actor) {
        request.setMode(KitImportMode.INSTALL);
        return importKit(tenantId, request, actor);
    }

    /** Convenience wrapper: forces {@link KitImportMode#UPDATE}. */
    public KitOperationResultDto update(
            String tenantId, KitImportRequestDto request, @Nullable String actor) {
        request.setMode(KitImportMode.UPDATE);
        return importKit(tenantId, request, actor);
    }

    /** Convenience wrapper: forces {@link KitImportMode#APPLY}. */
    public KitOperationResultDto apply(
            String tenantId, KitImportRequestDto request, @Nullable String actor) {
        request.setMode(KitImportMode.APPLY);
        return importKit(tenantId, request, actor);
    }

    /**
     * Export the active kit's top-layer back to a git remote. Uses the
     * manifest's {@code origin} for url/path/branch defaults when
     * {@link KitExportRequestDto} fields are blank.
     */
    public KitOperationResultDto export(
            String tenantId, KitExportRequestDto request, @Nullable String actor) {
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            throw new KitException("export request must carry a projectId");
        }
        if (projectService.findByTenantAndName(tenantId, request.getProjectId()).isEmpty()) {
            throw new KitException("project '" + request.getProjectId()
                    + "' does not exist in tenant '" + tenantId + "'");
        }
        return exporter.export(tenantId, request.getProjectId(), request, actor);
    }

    /**
     * Returns the project's active kit-manifest or {@code null} if no
     * kit is installed.
     */
    public @Nullable KitManifestDto status(String tenantId, String projectId) {
        return installer.loadManifest(tenantId, projectId);
    }

    // ──────────────────── validation ────────────────────

    private static void validateImport(KitImportRequestDto request) {
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            throw new KitException("kit request must carry a projectId");
        }
        KitInheritDto source = request.getSource();
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            throw new KitException("kit request must carry a source url");
        }
        if (request.getMode() == null) {
            throw new KitException("kit request must carry a mode");
        }
    }
}
