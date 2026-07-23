package de.mhus.vance.shared.kit.catalog;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Read/write access to the tenant-wide tool-templates catalog. Persisted
 * at {@code _tenant/config/tool-templates.yaml} — the authoritative
 * list of templates the Web-UI wizard and the chat-agent's
 * {@code tool_template_list} surface to users.
 *
 * <p>Parallel to {@link ProjectKitsCatalogService} but a separate file
 * because the two concepts are orthogonal: project kits initialise a
 * whole project, tool templates are additive configurations slotted
 * into existing projects. Same code shape, different
 * {@link #CATALOG_PATH}.
 *
 * <p>Spec: {@code planning/tool-templates.md} §3.3.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolTemplateCatalogService {

    /** Document path inside the {@code _tenant} project. */
    public static final String CATALOG_PATH = "_vance/config/tool-templates.yaml";

    /** Currently supported schema version. */
    public static final int CURRENT_VERSION = 1;

    private final DocumentService documentService;

    /**
     * Load the catalog for {@code tenantId}. Missing document → empty
     * catalog (version = {@link #CURRENT_VERSION}, no entries).
     */
    public ToolTemplateCatalogDto load(String tenantId) {
        Optional<DocumentDocument> doc = documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, CATALOG_PATH);
        if (doc.isEmpty()) {
            return ToolTemplateCatalogDto.builder()
                    .version(CURRENT_VERSION)
                    .templates(new ArrayList<>())
                    .build();
        }
        String yamlText = documentService.readContent(doc.get());
        if (StringUtils.isBlank(yamlText)) {
            return ToolTemplateCatalogDto.builder()
                    .version(CURRENT_VERSION)
                    .templates(new ArrayList<>())
                    .build();
        }
        return parse(yamlText, tenantId);
    }

    /** Look up a single entry by {@code name}. Null when missing. */
    public @Nullable ToolTemplateCatalogEntry findByName(String tenantId, String name) {
        if (StringUtils.isBlank(name)) return null;
        for (ToolTemplateCatalogEntry entry : load(tenantId).getTemplates()) {
            if (name.equals(entry.getName())) return entry;
        }
        return null;
    }

    /**
     * Persist {@code catalog} for {@code tenantId}. Validates structure
     * (see {@link #validate}), serializes to YAML, upserts the document.
     * Caller is responsible for the {@code _tenant} project existing —
     * this method does not create it.
     */
    public void save(String tenantId, ToolTemplateCatalogDto catalog) {
        validate(catalog);
        String yamlText = serialize(catalog);
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, CATALOG_PATH);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(), null, null, yamlText, null,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
            log.info("Updated tool-templates catalog tenantId='{}' templates={}",
                    tenantId, catalog.getTemplates().size());
        } else {
            documentService.createText(
                    tenantId,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    CATALOG_PATH,
                    "Tool Templates",
                    null,
                    yamlText,
                    null,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
            log.info("Created tool-templates catalog tenantId='{}' templates={}",
                    tenantId, catalog.getTemplates().size());
        }
    }

    // ──────────────────── validation ────────────────────

    private void validate(ToolTemplateCatalogDto catalog) {
        if (catalog == null) throw new IllegalArgumentException("Catalog is null");
        if (catalog.getVersion() != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported catalog version: " + catalog.getVersion()
                            + " (expected " + CURRENT_VERSION + ")");
        }
        List<ToolTemplateCatalogEntry> entries = catalog.getTemplates();
        if (entries == null) return;
        Set<String> seen = new HashSet<>();
        for (ToolTemplateCatalogEntry entry : entries) {
            if (entry == null) throw new IllegalArgumentException("Catalog contains a null entry");
            if (StringUtils.isBlank(entry.getName())) {
                throw new IllegalArgumentException("Catalog entry has blank name");
            }
            KitInheritDto src = entry.getSource();
            if (src == null || StringUtils.isBlank(src.getUrl())) {
                throw new IllegalArgumentException(
                        "Catalog entry '" + entry.getName() + "' has blank source url");
            }
            if (!seen.add(entry.getName())) {
                throw new IllegalArgumentException(
                        "Duplicate catalog entry name: '" + entry.getName() + "'");
            }
        }
    }

    // ──────────────────── YAML codec ────────────────────

    private ToolTemplateCatalogDto parse(String yamlText, String tenantId) {
        Object loaded = new Yaml().load(yamlText);
        if (loaded == null) {
            return ToolTemplateCatalogDto.builder()
                    .version(CURRENT_VERSION)
                    .templates(new ArrayList<>())
                    .build();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "Catalog at " + tenantId + "/" + HomeBootstrapService.TENANT_PROJECT_NAME
                            + "/" + CATALOG_PATH + " is not a YAML map");
        }
        int version = readInt(map, "version", CURRENT_VERSION);
        List<ToolTemplateCatalogEntry> entries = new ArrayList<>();
        Object node = map.get("templates");
        if (node instanceof List<?> list) {
            for (Object n : list) {
                if (n instanceof Map<?, ?> entryMap) {
                    entries.add(readEntry(entryMap));
                }
            }
        }
        return ToolTemplateCatalogDto.builder()
                .version(version)
                .templates(entries)
                .build();
    }

    private ToolTemplateCatalogEntry readEntry(Map<?, ?> entryMap) {
        return ToolTemplateCatalogEntry.builder()
                .name(readString(entryMap, "name"))
                .title(readNullableString(entryMap, "title"))
                .description(readNullableString(entryMap, "description"))
                .category(readNullableString(entryMap, "category"))
                .source(readSource(entryMap.get("source")))
                .build();
    }

    private KitInheritDto readSource(@Nullable Object node) {
        if (!(node instanceof Map<?, ?> map)) {
            return KitInheritDto.builder().url("").build();
        }
        return KitInheritDto.builder()
                .url(readNullableString(map, "url"))
                .path(readNullableString(map, "path"))
                .branch(readNullableString(map, "branch"))
                .commit(readNullableString(map, "commit"))
                .build();
    }

    private static @Nullable String readNullableString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static String readString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : v.toString();
    }

    private static int readInt(Map<?, ?> map, String key, int fallback) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    String serialize(ToolTemplateCatalogDto catalog) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", catalog.getVersion());
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ToolTemplateCatalogEntry entry : catalog.getTemplates()) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("name", entry.getName());
            if (!StringUtils.isBlank(entry.getTitle())) entryMap.put("title", entry.getTitle());
            if (!StringUtils.isBlank(entry.getDescription())) entryMap.put("description", entry.getDescription());
            if (!StringUtils.isBlank(entry.getCategory())) entryMap.put("category", entry.getCategory());
            KitInheritDto src = entry.getSource();
            if (src != null) {
                Map<String, Object> srcMap = new LinkedHashMap<>();
                srcMap.put("url", src.getUrl());
                if (!StringUtils.isBlank(src.getPath())) srcMap.put("path", src.getPath());
                if (!StringUtils.isBlank(src.getBranch())) srcMap.put("branch", src.getBranch());
                if (!StringUtils.isBlank(src.getCommit())) srcMap.put("commit", src.getCommit());
                entryMap.put("source", srcMap);
            }
            entries.add(entryMap);
        }
        root.put("templates", entries);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(root);
    }
}
