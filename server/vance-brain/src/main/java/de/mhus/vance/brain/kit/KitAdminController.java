package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.shared.access.AccessFilterBase;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST endpoints for the kit subsystem.
 *
 * <p>{@code GET /status} returns 204 when no kit is installed; all
 * mutation endpoints return the {@link KitOperationResultDto}.
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

    @GetMapping("/{projectId}/status")
    public ResponseEntity<KitManifestDto> status(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId) {
        KitManifestDto manifest = kitService.status(tenant, projectId);
        if (manifest == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(manifest);
    }

    @PostMapping("/{projectId}/install")
    public KitOperationResultDto install(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitImportRequestDto body,
            HttpServletRequest request) {
        return runImport(tenant, projectId, body, KitImportMode.INSTALL, request);
    }

    @PostMapping("/{projectId}/update")
    public KitOperationResultDto update(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitImportRequestDto body,
            HttpServletRequest request) {
        return runImport(tenant, projectId, body, KitImportMode.UPDATE, request);
    }

    @PostMapping("/{projectId}/apply")
    public KitOperationResultDto apply(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitImportRequestDto body,
            HttpServletRequest request) {
        return runImport(tenant, projectId, body, KitImportMode.APPLY, request);
    }

    @PostMapping("/{projectId}/export")
    public KitOperationResultDto export(
            @PathVariable("tenant") String tenant,
            @PathVariable("projectId") String projectId,
            @RequestBody KitExportRequestDto body,
            HttpServletRequest request) {
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
            return kitService.importKit(tenant, body, actor(request));
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
