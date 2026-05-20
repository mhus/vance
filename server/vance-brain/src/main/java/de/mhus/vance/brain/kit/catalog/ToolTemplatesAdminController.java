package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.ToolTemplateApplyRequestDto;
import de.mhus.vance.api.kit.ToolTemplateApplyResultDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.api.kit.ToolTemplateDescriptorDto;
import de.mhus.vance.api.kit.ToolTemplatePostInstallDto;
import de.mhus.vance.api.kit.ToolTemplatesScanRequestDto;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.brain.kit.TemplateApplier;
import de.mhus.vance.brain.kit.TemplateDescribeService;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.shared.kit.catalog.ToolTemplateCatalogService;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST endpoints for the tenant-wide tool-templates catalog plus
 * the apply path. Three concerns under one controller:
 *
 * <ul>
 *   <li>{@code GET    /catalog}            — current tenant catalog</li>
 *   <li>{@code PUT    /catalog}            — replace the catalog</li>
 *   <li>{@code POST   /scan}               — clone a git repo and return
 *       a fresh catalog DTO (without persisting), so anus can compose
 *       merge/overwrite/dry-run logic client-side</li>
 *   <li>{@code GET    /{name}}             — describe a template (parse template.yaml from the kit)</li>
 *   <li>{@code POST   /{name}/apply}       — apply with supplied inputs</li>
 * </ul>
 *
 * <p>Same Web-UI/agent/CLI surface — see {@code planning/tool-templates.md}.
 * Auth: tenant-level {@link Action#ADMIN} for catalog mutation;
 * project-level admin for apply.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin/tool-templates")
@RequiredArgsConstructor
@Slf4j
public class ToolTemplatesAdminController {

    private final ToolTemplateCatalogService catalogService;
    private final TemplateDescribeService describeService;
    private final ToolTemplateCatalogScanService scanService;
    private final KitService kitService;
    private final RequestAuthority authority;

    @GetMapping("/catalog")
    public ToolTemplateCatalogDto loadCatalog(
            @PathVariable("tenant") String tenant,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        return catalogService.load(tenant);
    }

    @PutMapping("/catalog")
    public ToolTemplateCatalogDto saveCatalog(
            @PathVariable("tenant") String tenant,
            @RequestBody ToolTemplateCatalogDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        try {
            catalogService.save(tenant, body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return catalogService.load(tenant);
    }

    @PostMapping("/scan")
    public ToolTemplateCatalogDto scan(
            @PathVariable("tenant") String tenant,
            @RequestBody ToolTemplatesScanRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        try {
            return scanService.scan(body.getGitUrl(), body.getRef(), body.getToken());
        } catch (KitException e) {
            log.warn("tool-templates catalog scan failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @GetMapping("/{name}")
    public ToolTemplateDescriptorDto describe(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            HttpServletRequest request) {
        authority.enforce(request, new Resource.Tenant(tenant), Action.ADMIN);
        ToolTemplateCatalogEntry entry = catalogService.findByName(tenant, name);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Tool template '" + name + "' is not in the tenant catalog");
        }
        try {
            return describeService.describe(entry.getSource(), null);
        } catch (KitException e) {
            log.warn("tool-template describe failed for '{}': {}", name, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/{name}/apply")
    public ToolTemplateApplyResultDto apply(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody ToolTemplateApplyRequestDto body,
            HttpServletRequest request) {
        authority.enforce(request,
                new Resource.Project(tenant, body.getProjectId()), Action.ADMIN);
        ToolTemplateCatalogEntry entry = catalogService.findByName(tenant, name);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Tool template '" + name + "' is not in the tenant catalog");
        }
        String actor = authority.contextOf(request).subjectId();
        try {
            TemplateApplier.ApplyResult result = kitService.applyTemplate(
                    tenant,
                    body.getProjectId(),
                    entry.getSource(),
                    body.getInputs() == null ? java.util.Map.of() : body.getInputs(),
                    body.getToken(),
                    actor);
            return ToolTemplateApplyResultDto.builder()
                    .templateName(result.templateName())
                    .installer(result.installer())
                    .postInstall(Optional.ofNullable(result.postInstall())
                            .map(pi -> ToolTemplatePostInstallDto.builder()
                                    .kind(pi.kind().name().toLowerCase().replace('_', '-'))
                                    .provider(pi.provider())
                                    .message(pi.message())
                                    .build())
                            .orElse(null))
                    .build();
        } catch (KitException e) {
            log.warn("tool-template apply '{}' failed for project='{}': {}",
                    name, body.getProjectId(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
