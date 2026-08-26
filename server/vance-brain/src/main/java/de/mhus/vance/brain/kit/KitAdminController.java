package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitLibraryEntryDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST endpoints for the kit subsystem.
 *
 * <p>{@code GET /status} lists the installed kits (empty list when
 * none); all mutation endpoints return the
 * {@link KitOperationResultDto}.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} before the
 * controller runs; the actor (current user) is read from the request
 * attribute populated by the access filter.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/kits")
@RequiredArgsConstructor
@Slf4j
public class KitAdminController {

    private final KitService kitService;
    private final KitLibraryService libraryService;
    private final RequestAuthority authority;

    /** The kits installed in this project, in layer order. */
    @GetMapping("/{projectId}/status")
    public List<KitInstalledRecordDto> status(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return kitService.status(tenant, projectId);
        } catch (KitException e) {
            // A kit problem must never reach the client as a 500 — this is the
            // endpoint the kit card in scopes.html reads, so an unexplained
            // error here is the one thing that stops the user fixing whatever
            // is broken.
            throw kitError(e);
        }
    }

    /**
     * The authoring manifest, i.e. the kit this project <i>is</i>.
     * 204 when the project is not a kit source — the normal case.
     */
    @GetMapping("/{projectId}/manifest")
    public ResponseEntity<KitManifestDto> manifest(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitManifestDto manifest;
        try {
            manifest = kitService.authoringManifest(tenant, projectId);
        } catch (KitException e) {
            throw kitError(e);
        }
        return manifest == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(manifest);
    }

    @PostMapping("/{projectId}/install")
    public KitOperationResultDto install(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitImportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        return runImport(tenant, projectId, body, KitImportMode.INSTALL, request);
    }

    @PostMapping("/{projectId}/update")
    public KitOperationResultDto update(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitImportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        return runImport(tenant, projectId, body, KitImportMode.UPDATE, request);
    }

    /**
     * Re-run one installed kit against its source. The record supplies
     * the coordinates, so the body only carries the knobs — token and
     * vault passphrase travel in the body rather than the query string
     * for the obvious reason.
     */
    @PostMapping("/{projectId}/update/{kitId}")
    public KitOperationResultDto updateOne(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("kitId") String kitId,
            @RequestBody(required = false) @Nullable KitImportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitImportRequestDto options = body == null ? new KitImportRequestDto() : body;
        try {
            return kitService.updateInstalled(tenant, projectId, kitId, options.isPrune(),
                    options.getToken(), options.getVaultPassword(),
                    actor(request), SettingWriteOrigin.USER);
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /** Re-run every installed kit of the project. One result entry per kit. */
    @PostMapping("/{projectId}/update-all")
    public List<KitOperationResultDto> updateAll(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody(required = false) @Nullable KitImportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitImportRequestDto options = body == null ? new KitImportRequestDto() : body;
        try {
            return kitService.updateAllInstalled(tenant, projectId, options.isPrune(),
                    options.getToken(), options.getVaultPassword(),
                    actor(request), SettingWriteOrigin.USER);
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /**
     * Re-apply all installed kits at their pinned versions, in layer
     * order. Repairs on-disk state; installs nothing newer.
     */
    @PostMapping("/{projectId}/reapply-all")
    public List<KitOperationResultDto> reapplyAll(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody(required = false) @Nullable KitImportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        KitImportRequestDto options = body == null ? new KitImportRequestDto() : body;
        try {
            return kitService.reapplyAll(tenant, projectId, options.getToken(),
                    options.getVaultPassword(), actor(request), SettingWriteOrigin.USER);
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /**
     * Forget one installed kit. Without {@code prune} the artefacts stay
     * in the project — the user may well have built on them.
     */
    @PostMapping("/{projectId}/uninstall/{kitId}")
    public KitOperationResultDto uninstall(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("kitId") String kitId,
            @RequestParam(name = "prune", defaultValue = "false") boolean prune,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return kitService.uninstall(tenant, projectId, kitId, prune, actor(request));
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /**
     * What this tenant may install from their configured libraries.
     *
     * <p>Fetched on request, never in the background: a library is a
     * remote service, and browsing one should not be something a
     * Vancetope install does on its own schedule.
     *
     * <p>A GET, now that there is nothing secret to send. It used to be a
     * POST so the library credential could travel in a body instead of a
     * query string; the credential comes from the caller's settings
     * instead, so the reason for the POST went with it.
     */
    @GetMapping("/{projectId}/library")
    public List<KitLibraryEntryDto> library(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return libraryService.list(tenant, projectId, actor(request));
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /**
     * The user's config for one installed kit — update policy and layer
     * order. Returns the defaults when no config document exists, so the
     * editor always has something to show.
     */
    @GetMapping("/{projectId}/config/{kitId}")
    public KitConfigDto config(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("kitId") String kitId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return kitService.loadConfig(tenant, projectId, kitId);
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    @PutMapping("/{projectId}/config/{kitId}")
    public KitConfigDto saveConfig(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("kitId") String kitId,
            @RequestBody KitConfigDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            kitService.saveConfig(tenant, projectId, kitId, body, actor(request));
            return kitService.loadConfig(tenant, projectId, kitId);
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /**
     * Convert a pre-multi-kit {@code _vance/kit-manifest.yaml} into an
     * install record. Only meaningful for projects that were set up before
     * the rework; a no-op with an explanation everywhere else.
     */
    @PostMapping("/{projectId}/migrate-legacy")
    public KitLegacyMigrator.Result migrateLegacy(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestParam(name = "keepAsKitSource", defaultValue = "false") boolean keepAsKitSource,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return kitService.migrateLegacy(tenant, projectId, keepAsKitSource, actor(request));
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    /** Make an installed kit this project's kit source, so it can be exported. */
    @PostMapping("/{projectId}/promote/{kitId}")
    public KitManifestDto promote(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @PathVariable("kitId") String kitId,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        try {
            return kitService.promoteToAuthoring(tenant, projectId, kitId, actor(request));
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    @PostMapping("/{projectId}/apply")
    public KitOperationResultDto apply(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitImportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        return runImport(tenant, projectId, body, KitImportMode.APPLY, request);
    }

    @PostMapping("/{projectId}/export")
    public KitOperationResultDto export(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitExportRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Project(tenant, projectId), Action.ADMIN);
        body.setProjectId(projectId);
        try {
            return kitService.export(tenant, body, actor(request));
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    // ──────────────────── helpers ────────────────────

    private KitOperationResultDto runImport(
            String tenant, String projectId,
            KitImportRequestDto body, KitImportMode mode,
            HttpServletRequest request) {
        body.setProjectId(projectId);
        body.setMode(mode);
        try {
            return kitService.importKit(tenant, body, actor(request),
                    SettingWriteOrigin.USER);
        } catch (KitException e) {
            throw kitError(e);
        }
    }

    private static @Nullable String actor(HttpServletRequest request) {
        Object u = request.getAttribute(AccessFilterBase.ATTR_USERNAME);
        return u == null ? null : u.toString();
    }

    private static ResponseStatusException kitError(KitException e) {
        log.warn("kit operation failed: {}", e.getMessage());
        // KitException is the catch-all for user-facing kit problems
        // — bad URL, missing manifest, malformed kit.yaml, vault
        // mismatch. Map all of them to 400 so the web client can
        // surface the message verbatim.
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
}
