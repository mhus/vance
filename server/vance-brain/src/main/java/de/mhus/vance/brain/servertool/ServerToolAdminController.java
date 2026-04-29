package de.mhus.vance.brain.servertool;

import de.mhus.vance.api.servertools.ServerToolDto;
import de.mhus.vance.api.servertools.ServerToolWriteRequest;
import de.mhus.vance.api.servertools.ToolTypeDto;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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

    // ─── Tool-type discovery ───────────────────────────────────────────────

    @GetMapping("/server-tool-types")
    public List<ToolTypeDto> listTypes(
            @PathVariable("tenant") String tenant) {
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
            @PathVariable("project") String project) {
        return serverToolService.listDocuments(tenant, project).stream()
                .map(ServerToolAdminController::toDto)
                .sorted(Comparator.comparing(ServerToolDto::getName))
                .toList();
    }

    @GetMapping("/projects/{project}/server-tools/{name}")
    public ServerToolDto getProjectTool(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name) {
        ServerToolDocument doc = serverToolService.findDocument(tenant, project, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No server tool '" + name + "' in project '" + project + "'"));
        return toDto(doc);
    }

    @PutMapping("/projects/{project}/server-tools/{name}")
    public ServerToolDto upsertProjectTool(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @Valid @RequestBody ServerToolWriteRequest request) {
        Optional<ServerToolDocument> existing =
                serverToolService.findDocument(tenant, project, name);
        try {
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
            @PathVariable("name") String name) {
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
                .projectId(doc.getProjectId())
                .updatedAtTimestamp(doc.getUpdatedAt() == null
                        ? null
                        : doc.getUpdatedAt().toEpochMilli())
                .createdBy(doc.getCreatedBy())
                .build();
    }
}
