package de.mhus.vance.brain.servertool;

import de.mhus.vance.api.servertools.ServerToolDto;
import de.mhus.vance.api.servertools.ServerToolWriteRequest;
import de.mhus.vance.api.servertools.ToolTypeDto;
import de.mhus.vance.brain.permission.RequestAuthority;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.permission.Action;
import de.mhus.vance.shared.permission.Resource;
import de.mhus.vance.shared.servertool.ServerToolConfig;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST for {@link ServerToolDocument}. CRUD is project-scoped —
 * pick the {@code _vance} system project for tenant-wide defaults,
 * any user project for a project-local override or new tool.
 *
 * <p>Built-in bean tools (the {@code Tool}-implementing Spring beans
 * that ship with the brain) are not exposed here — they are not
 * persisted and therefore not editable. The runtime cascade in
 * {@link ServerToolService} still merges them in for tool-dispatch.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the
 * JWT's {@code tid} claim before requests reach this controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin")
@RequiredArgsConstructor
@Slf4j
public class ServerToolAdminController {

    private final ServerToolService serverToolService;
    private final RequestAuthority authority;

    // ─── Tool-type discovery ───────────────────────────────────────────────

    @GetMapping("/server-tool-types")
    public List<ToolTypeDto> listTypes(
            @PathVariable("tenant") String tenant,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Tenant(tenant), Action.ADMIN);
        List<ToolTypeDto> out = new ArrayList<>();
        for (ToolFactory factory : serverToolService.listTypes()) {
            out.add(ToolTypeDto.builder()
                    .typeId(factory.typeId())
                    .parametersSchema(factory.parametersSchema())
                    .build());
        }
        out.sort(Comparator.comparing(ToolTypeDto::getTypeId));
        return out;
    }

    // ─── Project-scoped CRUD ───────────────────────────────────────────────

    @GetMapping("/projects/{project}/server-tools")
    public List<ServerToolDto> listProjectTools(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.ADMIN);
        // Build DTOs from the cascade-resolved configs so each entry
        // carries its actual {@code source} tier (PROJECT / TENANT /
        // BUNDLED). Going through {@code listDocuments()} would lose
        // it — the transient document carrier stamps the requesting
        // project's id as {@code projectId} regardless of where the
        // config actually came from.
        return serverToolService.listConfigs(tenant, project).stream()
                .map(cfg -> toDtoFromConfig(cfg, project))
                .sorted(Comparator.comparing(ServerToolDto::getName))
                .toList();
    }

    @GetMapping("/projects/{project}/server-tools/{name}")
    public ServerToolDto getProjectTool(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.ADMIN);
        ServerToolConfig cfg = serverToolService.findConfig(tenant, project, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No server tool '" + name + "' in project '" + project + "'"));
        return toDtoFromConfig(cfg, project);
    }

    @PutMapping("/projects/{project}/server-tools/{name}")
    public ServerToolDto upsertProjectTool(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @Valid @RequestBody ServerToolWriteRequest request,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.ADMIN);
        Optional<ServerToolDocument> existing =
                serverToolService.findDocument(tenant, project, name);
        try {
            Set<String> disabledSubTools = request.getDisabledSubTools() == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(request.getDisabledSubTools());
            ServerToolDocument saved;
            if (existing.isPresent()) {
                ServerToolDocument incoming = ServerToolDocument.builder()
                        .name(name)
                        .type(request.getType())
                        .description(request.getDescription())
                        .parameters(new LinkedHashMap<>(request.getParameters()))
                        .labels(new ArrayList<>(request.getLabels()))
                        .enabled(request.isEnabled())
                        .primary(request.isPrimary())
                        .disabledSubTools(disabledSubTools)
                        .defaultDeferred(request.isDefaultDeferred())
                        .promptHint(request.getPromptHint() == null
                                ? "" : request.getPromptHint())
                        .build();
                saved = serverToolService.update(tenant, project, name, incoming);
                log.info("Updated server tool tenant='{}' project='{}' name='{}'",
                        tenant, project, name);
            } else {
                ServerToolDocument fresh = ServerToolDocument.builder()
                        .name(name)
                        .type(request.getType())
                        .description(request.getDescription())
                        .parameters(new LinkedHashMap<>(request.getParameters()))
                        .labels(new ArrayList<>(request.getLabels()))
                        .enabled(request.isEnabled())
                        .primary(request.isPrimary())
                        .disabledSubTools(disabledSubTools)
                        .defaultDeferred(request.isDefaultDeferred())
                        .promptHint(request.getPromptHint() == null
                                ? "" : request.getPromptHint())
                        .build();
                saved = serverToolService.create(tenant, project, fresh);
                log.info("Created server tool tenant='{}' project='{}' name='{}'",
                        tenant, project, name);
            }
            return toDto(saved);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping("/projects/{project}/server-tools/{name}")
    public ResponseEntity<Void> deleteProjectTool(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            HttpServletRequest httpRequest) {
        authority.enforce(httpRequest, new Resource.Project(tenant, project), Action.ADMIN);
        if (serverToolService.findDocument(tenant, project, name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        serverToolService.delete(tenant, project, name);
        log.info("Deleted server tool tenant='{}' project='{}' name='{}'",
                tenant, project, name);
        return ResponseEntity.noContent().build();
    }

    // ─── Mapping ───────────────────────────────────────────────────────────

    private static ServerToolDto toDto(ServerToolDocument doc) {
        // Save-flow path: caller always wrote into the requesting
        // project, so the resulting DTO is project-sourced.
        return baseBuilder(doc)
                .source("PROJECT")
                .build();
    }

    private static ServerToolDto.ServerToolDtoBuilder baseBuilder(ServerToolDocument doc) {
        return ServerToolDto.builder()
                .name(doc.getName())
                .type(doc.getType())
                .description(doc.getDescription())
                .parameters(doc.getParameters() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(doc.getParameters()))
                .labels(doc.getLabels() == null
                        ? new ArrayList<>()
                        : new ArrayList<>(doc.getLabels()))
                .enabled(doc.isEnabled())
                .primary(doc.isPrimary())
                .disabledSubTools(doc.getDisabledSubTools() == null
                        ? new LinkedHashSet<>()
                        : new LinkedHashSet<>(doc.getDisabledSubTools()))
                .defaultDeferred(doc.isDefaultDeferred())
                .promptHint(doc.getPromptHint() == null ? "" : doc.getPromptHint())
                .projectId(doc.getProjectId())
                // Timestamps now live on the underlying DocumentDocument
                // (server-tools/<name>.yaml) and aren't carried by the
                // transient config shape. The DTO field is preserved for
                // API stability — admin clients that want audit timestamps
                // can read them from the documents endpoint.
                .updatedAtTimestamp(null)
                .createdBy(doc.getCreatedBy());
    }

    /**
     * Build a DTO from a cascade-resolved config. The cascade tier
     * ({@link ServerToolConfig.Source}) is mapped to a stable API
     * string ({@code PROJECT} / {@code TENANT} / {@code BUNDLED}) so
     * the admin UI can render a source-badge per row and warn before
     * an edit creates an override of a cascaded entry.
     *
     * <p>{@code projectId} carries the source project name where it
     * makes sense (PROJECT / TENANT cases); for bundled defaults the
     * field falls back to the requesting {@code projectId} since
     * classpath entries don't belong to a project document.
     */
    private static ServerToolDto toDtoFromConfig(ServerToolConfig cfg, String requestingProject) {
        ServerToolDocument doc = cfg.toTransientDocument("", requestingProject);
        String source = switch (cfg.source()) {
            case PROJECT -> "PROJECT";
            case VANCE   -> "TENANT";
            case RESOURCE -> "BUNDLED";
        };
        // Project-id reflects the actual owner — handy for the UI to
        // know whether saving would touch the current project or
        // create a new override here.
        String ownerProject = switch (cfg.source()) {
            case PROJECT -> requestingProject;
            case VANCE   -> "_tenant";
            case RESOURCE -> requestingProject;
        };
        return baseBuilder(doc)
                .projectId(ownerProject)
                .source(source)
                .build();
    }
}
