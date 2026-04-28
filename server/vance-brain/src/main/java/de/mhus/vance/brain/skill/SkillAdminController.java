package de.mhus.vance.brain.skill;

import de.mhus.vance.api.skills.SkillDto;
import de.mhus.vance.api.skills.SkillReferenceDocDto;
import de.mhus.vance.api.skills.SkillScope;
import de.mhus.vance.api.skills.SkillTriggerDto;
import de.mhus.vance.api.skills.SkillTriggerType;
import de.mhus.vance.api.skills.SkillWriteRequest;
import de.mhus.vance.shared.access.AccessFilterBase;
import de.mhus.vance.shared.skill.SkillDocument;
import de.mhus.vance.shared.skill.SkillReferenceDocEmbedded;
import de.mhus.vance.shared.skill.SkillService;
import de.mhus.vance.shared.skill.SkillTriggerEmbedded;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST for skills.
 *
 * <p>The {@code effective} endpoint walks BUNDLED → TENANT → PROJECT → USER
 * (lower-to-higher precedence) and returns one DTO per unique name with
 * {@link SkillScope} indicating where this copy lives. Per-scope CRUD
 * endpoints operate strictly on their own tier — bundled skills are
 * immutable and only readable through the effective view.
 *
 * <p>USER-scope reads, writes and deletes are restricted to the JWT-
 * authenticated caller — a user cannot inspect or mutate another user's
 * private skills through this controller.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the
 * JWT's {@code tid} claim before requests reach this controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin")
@RequiredArgsConstructor
@Slf4j
public class SkillAdminController {

    private final SkillService skillService;
    private final BundledSkillRegistry bundledRegistry;

    // ─── Effective list (cascade-resolved) ─────────────────────────────────

    @GetMapping("/skills/effective")
    public List<SkillDto> listEffective(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId,
            @RequestParam(value = "userId", required = false) @Nullable String userId,
            HttpServletRequest req) {

        if (userId != null && !userId.isBlank() && !userId.equals(currentUser(req))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cannot list another user's skills");
        }

        // Bottom of the cascade: bundled, keyed by name.
        Map<String, SkillDto> byName = new LinkedHashMap<>();
        for (BundledSkill b : bundledRegistry.all()) {
            byName.put(b.name(), toDto(b));
        }
        // Overlay tenant entries.
        for (SkillDocument t : skillService.listTenant(tenant)) {
            byName.put(t.getName(), toDto(t));
        }
        // Overlay project entries when a project is given.
        if (projectId != null && !projectId.isBlank()) {
            for (SkillDocument p : skillService.listProject(tenant, projectId)) {
                byName.put(p.getName(), toDto(p));
            }
        }
        // Overlay user entries when a user is given.
        if (userId != null && !userId.isBlank()) {
            for (SkillDocument u : skillService.listUser(tenant, userId)) {
                byName.put(u.getName(), toDto(u));
            }
        }
        return byName.values().stream()
                .sorted(Comparator.comparing(SkillDto::getName))
                .toList();
    }

    // ─── Tenant-scope CRUD ─────────────────────────────────────────────────

    @GetMapping("/skills/{name}")
    public SkillDto getTenantSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        SkillDocument doc = skillService.find(tenant, null, null, name)
                .filter(d -> d.getScope() == SkillScope.TENANT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No tenant skill '" + name + "'"));
        return toDto(doc);
    }

    @PutMapping("/skills/{name}")
    public SkillDto upsertTenantSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody SkillWriteRequest request) {
        SkillDocument doc = skillService.find(tenant, null, null, name)
                .filter(d -> d.getScope() == SkillScope.TENANT)
                .orElseGet(() -> SkillDocument.builder()
                        .tenantId(tenant)
                        .scope(SkillScope.TENANT)
                        .name(name)
                        .build());
        applyWrite(doc, request);
        SkillDocument saved = skillService.save(doc);
        return toDto(saved);
    }

    @DeleteMapping("/skills/{name}")
    public ResponseEntity<Void> deleteTenantSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        Optional<SkillDocument> existing = skillService.find(tenant, null, null, name)
                .filter(d -> d.getScope() == SkillScope.TENANT);
        if (existing.isEmpty() || existing.get().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        skillService.delete(existing.get().getId());
        log.info("Deleted tenant skill tenant='{}' name='{}'", tenant, name);
        return ResponseEntity.noContent().build();
    }

    // ─── Project-scope CRUD ────────────────────────────────────────────────

    @GetMapping("/projects/{project}/skills/{name}")
    public SkillDto getProjectSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name) {
        SkillDocument doc = skillService.listProject(tenant, project).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No project skill '" + name + "' in project '" + project + "'"));
        return toDto(doc);
    }

    @PutMapping("/projects/{project}/skills/{name}")
    public SkillDto upsertProjectSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @Valid @RequestBody SkillWriteRequest request) {
        SkillDocument doc = skillService.listProject(tenant, project).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseGet(() -> SkillDocument.builder()
                        .tenantId(tenant)
                        .scope(SkillScope.PROJECT)
                        .projectId(project)
                        .name(name)
                        .build());
        applyWrite(doc, request);
        SkillDocument saved = skillService.save(doc);
        return toDto(saved);
    }

    @DeleteMapping("/projects/{project}/skills/{name}")
    public ResponseEntity<Void> deleteProjectSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name) {
        Optional<SkillDocument> existing = skillService.listProject(tenant, project).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst();
        if (existing.isEmpty() || existing.get().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        skillService.delete(existing.get().getId());
        log.info("Deleted project skill tenant='{}' project='{}' name='{}'",
                tenant, project, name);
        return ResponseEntity.noContent().build();
    }

    // ─── User-scope CRUD (own user only) ───────────────────────────────────

    @GetMapping("/users/{userId}/skills/{name}")
    public SkillDto getUserSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("userId") String userId,
            @PathVariable("name") String name,
            HttpServletRequest req) {
        requireOwnUser(req, userId);
        SkillDocument doc = skillService.listUser(tenant, userId).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No user skill '" + name + "' for user '" + userId + "'"));
        return toDto(doc);
    }

    @PutMapping("/users/{userId}/skills/{name}")
    public SkillDto upsertUserSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("userId") String userId,
            @PathVariable("name") String name,
            @Valid @RequestBody SkillWriteRequest request,
            HttpServletRequest req) {
        requireOwnUser(req, userId);
        SkillDocument doc = skillService.listUser(tenant, userId).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseGet(() -> SkillDocument.builder()
                        .tenantId(tenant)
                        .scope(SkillScope.USER)
                        .userId(userId)
                        .name(name)
                        .build());
        applyWrite(doc, request);
        SkillDocument saved = skillService.save(doc);
        return toDto(saved);
    }

    @DeleteMapping("/users/{userId}/skills/{name}")
    public ResponseEntity<Void> deleteUserSkill(
            @PathVariable("tenant") String tenant,
            @PathVariable("userId") String userId,
            @PathVariable("name") String name,
            HttpServletRequest req) {
        requireOwnUser(req, userId);
        Optional<SkillDocument> existing = skillService.listUser(tenant, userId).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst();
        if (existing.isEmpty() || existing.get().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        skillService.delete(existing.get().getId());
        log.info("Deleted user skill tenant='{}' userId='{}' name='{}'",
                tenant, userId, name);
        return ResponseEntity.noContent().build();
    }

    // ─── Auth helpers ──────────────────────────────────────────────────────

    private static String currentUser(HttpServletRequest req) {
        Object u = req.getAttribute(AccessFilterBase.ATTR_USERNAME);
        if (!(u instanceof String s) || s.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "No authenticated user");
        }
        return s;
    }

    private static void requireOwnUser(HttpServletRequest req, String userId) {
        if (!userId.equals(currentUser(req))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Cannot manage another user's skills");
        }
    }

    // ─── Mapping helpers ───────────────────────────────────────────────────

    private static SkillDto toDto(BundledSkill b) {
        return SkillDto.builder()
                .name(b.name())
                .title(b.title())
                .description(b.description())
                .version(b.version())
                .triggers(b.triggers().stream()
                        .map(SkillAdminController::toDto)
                        .toList())
                .promptExtension(b.promptExtension())
                .tools(new ArrayList<>(b.tools()))
                .referenceDocs(b.referenceDocs().stream()
                        .map(SkillAdminController::toDto)
                        .toList())
                .tags(new ArrayList<>(b.tags()))
                .enabled(b.enabled())
                .scope(SkillScope.BUNDLED)
                .build();
    }

    private static SkillDto toDto(SkillDocument d) {
        return SkillDto.builder()
                .name(d.getName())
                .title(d.getTitle())
                .description(d.getDescription())
                .version(d.getVersion())
                .triggers(d.getTriggers().stream()
                        .map(SkillAdminController::toDto)
                        .toList())
                .promptExtension(d.getPromptExtension())
                .tools(new ArrayList<>(d.getTools()))
                .referenceDocs(d.getReferenceDocs().stream()
                        .map(SkillAdminController::toDto)
                        .toList())
                .tags(new ArrayList<>(d.getTags()))
                .enabled(d.isEnabled())
                .scope(d.getScope())
                .projectId(d.getScope() == SkillScope.PROJECT ? d.getProjectId() : null)
                .userId(d.getScope() == SkillScope.USER ? d.getUserId() : null)
                .build();
    }

    private static SkillTriggerDto toDto(BundledSkill.Trigger t) {
        return SkillTriggerDto.builder()
                .type(t.type())
                .pattern(t.pattern())
                .keywords(new ArrayList<>(t.keywords()))
                .build();
    }

    private static SkillTriggerDto toDto(SkillTriggerEmbedded t) {
        return SkillTriggerDto.builder()
                .type(t.getType())
                .pattern(t.getPattern())
                .keywords(new ArrayList<>(t.getKeywords()))
                .build();
    }

    private static SkillReferenceDocDto toDto(BundledSkill.ReferenceDoc r) {
        return SkillReferenceDocDto.builder()
                .title(r.title())
                .content(r.content())
                .loadMode(r.loadMode())
                .build();
    }

    private static SkillReferenceDocDto toDto(SkillReferenceDocEmbedded r) {
        return SkillReferenceDocDto.builder()
                .title(r.getTitle())
                .content(r.getContent())
                .loadMode(r.getLoadMode())
                .build();
    }

    private static void applyWrite(SkillDocument doc, SkillWriteRequest r) {
        doc.setTitle(r.getTitle());
        doc.setDescription(r.getDescription());
        doc.setVersion(r.getVersion());
        doc.setTriggers(r.getTriggers().stream()
                .map(SkillAdminController::fromDto)
                .toList());
        doc.setPromptExtension(r.getPromptExtension());
        doc.setTools(new ArrayList<>(r.getTools()));
        doc.setReferenceDocs(r.getReferenceDocs().stream()
                .map(SkillAdminController::fromDto)
                .toList());
        doc.setTags(new ArrayList<>(r.getTags()));
        doc.setEnabled(r.isEnabled());
    }

    private static SkillTriggerEmbedded fromDto(SkillTriggerDto t) {
        SkillTriggerType type = t.getType();
        return SkillTriggerEmbedded.builder()
                .type(type == null ? SkillTriggerType.PATTERN : type)
                .pattern(t.getPattern())
                .keywords(t.getKeywords() == null ? new ArrayList<>() : new ArrayList<>(t.getKeywords()))
                .build();
    }

    private static SkillReferenceDocEmbedded fromDto(SkillReferenceDocDto r) {
        return SkillReferenceDocEmbedded.builder()
                .title(r.getTitle())
                .content(r.getContent())
                .loadMode(r.getLoadMode())
                .build();
    }
}
