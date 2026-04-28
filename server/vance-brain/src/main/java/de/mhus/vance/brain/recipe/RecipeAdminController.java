package de.mhus.vance.brain.recipe;

import de.mhus.vance.api.recipes.RecipeDto;
import de.mhus.vance.api.recipes.RecipeSource;
import de.mhus.vance.api.recipes.RecipeWriteRequest;
import de.mhus.vance.shared.recipe.RecipeDocument;
import de.mhus.vance.shared.recipe.RecipeScope;
import de.mhus.vance.shared.recipe.RecipeService;
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
 * Admin REST for recipes.
 *
 * <p>The {@code effective} endpoint walks Project → Tenant → Bundled
 * and returns one DTO per unique name, marked with {@link RecipeSource}
 * so the UI can offer "edit" (own scope) vs. "override here" (inherited
 * from below). Per-scope CRUD endpoints operate strictly on their own
 * scope — bundled recipes are immutable and only readable through the
 * effective view.
 *
 * <p>Tenant in the path is validated by
 * {@link de.mhus.vance.brain.access.BrainAccessFilter} against the
 * JWT's {@code tid} claim before requests reach this controller.
 */
@RestController
@RequestMapping("/brain/{tenant}/admin")
@RequiredArgsConstructor
@Slf4j
public class RecipeAdminController {

    private final RecipeService recipeService;
    private final BundledRecipeRegistry bundledRegistry;

    // ─── Effective list (cascade-resolved) ─────────────────────────────────

    @GetMapping("/recipes/effective")
    public List<RecipeDto> listEffective(
            @PathVariable("tenant") String tenant,
            @RequestParam(value = "projectId", required = false) @Nullable String projectId) {

        // Bottom of the cascade: bundled, keyed by name.
        Map<String, RecipeDto> byName = new LinkedHashMap<>();
        for (BundledRecipe b : bundledRegistry.all()) {
            byName.put(b.name(), toDto(b));
        }
        // Overlay tenant entries.
        for (RecipeDocument t : recipeService.listTenant(tenant)) {
            byName.put(t.getName(), toDto(t, RecipeSource.TENANT));
        }
        // Overlay project entries when a project is given.
        if (projectId != null && !projectId.isBlank()) {
            for (RecipeDocument p : recipeService.listProject(tenant, projectId)) {
                byName.put(p.getName(), toDto(p, RecipeSource.PROJECT));
            }
        }
        return byName.values().stream()
                .sorted(Comparator.comparing(RecipeDto::getName))
                .toList();
    }

    // ─── Tenant-scope CRUD ─────────────────────────────────────────────────

    @GetMapping("/recipes/{name}")
    public RecipeDto getTenantRecipe(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        RecipeDocument doc = recipeService.find(tenant, null, name)
                .filter(d -> d.getScope() == RecipeScope.TENANT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No tenant recipe '" + name + "'"));
        return toDto(doc, RecipeSource.TENANT);
    }

    @PutMapping("/recipes/{name}")
    public RecipeDto upsertTenantRecipe(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name,
            @Valid @RequestBody RecipeWriteRequest request) {
        RecipeDocument doc = recipeService.find(tenant, null, name)
                .filter(d -> d.getScope() == RecipeScope.TENANT)
                .orElseGet(() -> RecipeDocument.builder()
                        .tenantId(tenant)
                        .scope(RecipeScope.TENANT)
                        .name(name)
                        .build());
        applyWrite(doc, request);
        RecipeDocument saved = recipeService.save(doc);
        return toDto(saved, RecipeSource.TENANT);
    }

    @DeleteMapping("/recipes/{name}")
    public ResponseEntity<Void> deleteTenantRecipe(
            @PathVariable("tenant") String tenant,
            @PathVariable("name") String name) {
        Optional<RecipeDocument> existing = recipeService.find(tenant, null, name)
                .filter(d -> d.getScope() == RecipeScope.TENANT);
        if (existing.isEmpty() || existing.get().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        recipeService.delete(existing.get().getId());
        log.info("Deleted tenant recipe tenant='{}' name='{}'", tenant, name);
        return ResponseEntity.noContent().build();
    }

    // ─── Project-scope CRUD ────────────────────────────────────────────────

    @GetMapping("/projects/{project}/recipes/{name}")
    public RecipeDto getProjectRecipe(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name) {
        RecipeDocument doc = recipeService.listProject(tenant, project).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No project recipe '" + name + "' in project '" + project + "'"));
        return toDto(doc, RecipeSource.PROJECT);
    }

    @PutMapping("/projects/{project}/recipes/{name}")
    public RecipeDto upsertProjectRecipe(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name,
            @Valid @RequestBody RecipeWriteRequest request) {
        RecipeDocument doc = recipeService.listProject(tenant, project).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElseGet(() -> RecipeDocument.builder()
                        .tenantId(tenant)
                        .scope(RecipeScope.PROJECT)
                        .projectId(project)
                        .name(name)
                        .build());
        applyWrite(doc, request);
        RecipeDocument saved = recipeService.save(doc);
        return toDto(saved, RecipeSource.PROJECT);
    }

    @DeleteMapping("/projects/{project}/recipes/{name}")
    public ResponseEntity<Void> deleteProjectRecipe(
            @PathVariable("tenant") String tenant,
            @PathVariable("project") String project,
            @PathVariable("name") String name) {
        Optional<RecipeDocument> existing = recipeService.listProject(tenant, project).stream()
                .filter(d -> d.getName().equals(name))
                .findFirst();
        if (existing.isEmpty() || existing.get().getId() == null) {
            return ResponseEntity.notFound().build();
        }
        recipeService.delete(existing.get().getId());
        log.info("Deleted project recipe tenant='{}' project='{}' name='{}'",
                tenant, project, name);
        return ResponseEntity.noContent().build();
    }

    // ─── Mapping helpers ───────────────────────────────────────────────────

    private static RecipeDto toDto(BundledRecipe b) {
        return RecipeDto.builder()
                .name(b.name())
                .description(b.description())
                .engine(b.engine())
                .params(b.params())
                .promptPrefix(b.promptPrefix())
                .promptPrefixSmall(b.promptPrefixSmall())
                .promptMode(b.promptMode())
                .intentCorrection(b.intentCorrection())
                .dataRelayCorrection(b.dataRelayCorrection())
                .allowedToolsAdd(b.allowedToolsAdd())
                .allowedToolsRemove(b.allowedToolsRemove())
                .locked(b.locked())
                .tags(b.tags())
                .source(RecipeSource.BUNDLED)
                .build();
    }

    private static RecipeDto toDto(RecipeDocument d, RecipeSource source) {
        return RecipeDto.builder()
                .name(d.getName())
                .description(d.getDescription())
                .engine(d.getEngine())
                .params(d.getParams())
                .promptPrefix(d.getPromptPrefix())
                .promptPrefixSmall(d.getPromptPrefixSmall())
                .promptMode(d.getPromptMode())
                .intentCorrection(d.getIntentCorrection())
                .dataRelayCorrection(d.getDataRelayCorrection())
                .allowedToolsAdd(d.getAllowedToolsAdd())
                .allowedToolsRemove(d.getAllowedToolsRemove())
                .locked(d.isLocked())
                .tags(d.getTags())
                .source(source)
                .projectId(source == RecipeSource.PROJECT ? d.getProjectId() : null)
                .build();
    }

    private static void applyWrite(RecipeDocument doc, RecipeWriteRequest r) {
        doc.setDescription(r.getDescription());
        doc.setEngine(r.getEngine());
        doc.setParams(new LinkedHashMap<>(r.getParams()));
        doc.setPromptPrefix(r.getPromptPrefix());
        doc.setPromptPrefixSmall(r.getPromptPrefixSmall());
        doc.setPromptMode(r.getPromptMode());
        doc.setIntentCorrection(r.getIntentCorrection());
        doc.setDataRelayCorrection(r.getDataRelayCorrection());
        doc.setAllowedToolsAdd(new ArrayList<>(r.getAllowedToolsAdd()));
        doc.setAllowedToolsRemove(new ArrayList<>(r.getAllowedToolsRemove()));
        doc.setLocked(r.isLocked());
        doc.setTags(new ArrayList<>(r.getTags()));
    }
}
