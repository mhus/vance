package de.mhus.vance.shared.kit.catalog;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.tenant.TenantService;
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
 * Read/write access to the tenant-wide project-kits catalog, the only
 * authoritative path to {@code config/project-kits.yaml} in any
 * tenant's {@code _vance} project.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectKitsCatalogService {

    /** Document path inside the {@code _vance} project. */
    public static final String CATALOG_PATH = "config/project-kits.yaml";

    /** Currently supported schema version. */
    public static final int CURRENT_VERSION = 1;

    private final DocumentService documentService;

    /**
     * Load the catalog for {@code tenantId}. Missing document → empty
     * catalog (version = {@link #CURRENT_VERSION}, no entries).
     */
    public ProjectKitsCatalogDto load(String tenantId) {
        Optional<DocumentDocument> doc = documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, CATALOG_PATH);
        if (doc.isEmpty()) {
            return ProjectKitsCatalogDto.builder()
                    .version(CURRENT_VERSION)
                    .kits(new ArrayList<>())
                    .build();
        }
        String yamlText = documentService.readContent(doc.get());
        if (StringUtils.isBlank(yamlText)) {
            return ProjectKitsCatalogDto.builder()
                    .version(CURRENT_VERSION)
                    .kits(new ArrayList<>())
                    .build();
        }
        return parse(yamlText, tenantId);
    }

    /**
     * Look up a single entry by {@code name}. Returns {@code null} if
     * the catalog is missing or no entry matches.
     */
    public @Nullable ProjectKitEntry findByName(String tenantId, String name) {
        if (StringUtils.isBlank(name)) return null;
        for (ProjectKitEntry entry : load(tenantId).getKits()) {
            if (name.equals(entry.getName())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Persist {@code catalog} for {@code tenantId}. Validates content
     * (see {@link #validate}), serializes to YAML, upserts the document.
     * Caller is responsible for having the tenant's {@code _vance}
     * project provisioned — this method does <b>not</b> create it.
     */
    public void save(String tenantId, ProjectKitsCatalogDto catalog) {
        validate(catalog);
        String yamlText = serialize(catalog);
        Optional<DocumentDocument> existing = documentService.findByPath(
                tenantId, HomeBootstrapService.TENANT_PROJECT_NAME, CATALOG_PATH);
        if (existing.isPresent()) {
            documentService.update(
                    existing.get().getId(), null, null, yamlText, null);
            log.info("Updated project-kits catalog tenantId='{}' kits={}",
                    tenantId, catalog.getKits().size());
        } else {
            documentService.createText(
                    tenantId,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    CATALOG_PATH,
                    "Project Kits",
                    null,
                    yamlText,
                    null);
            log.info("Created project-kits catalog tenantId='{}' kits={}",
                    tenantId, catalog.getKits().size());
        }
    }

    /**
     * Idempotent seed: if {@code targetTenantId} does not yet have a
     * catalog document, copy the system tenant's catalog into it. Skips
     * silently when:
     * <ul>
     *   <li>target tenant is the system tenant itself,</li>
     *   <li>source catalog is missing,</li>
     *   <li>target catalog already exists,</li>
     *   <li>target {@code _vance} project does not exist yet (we don't
     *       force-create it here — the project is bootstrapped lazily by
     *       other code paths and will pick up the seed on the next call).</li>
     * </ul>
     */
    public void seedFromSystemTenant(String targetTenantId) {
        if (TenantService.SYSTEM_TENANT.equals(targetTenantId)) {
            return;
        }
        Optional<DocumentDocument> sourceDoc = documentService.findByPath(
                TenantService.SYSTEM_TENANT,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                CATALOG_PATH);
        if (sourceDoc.isEmpty()) {
            return;
        }
        Optional<DocumentDocument> targetDoc = documentService.findByPath(
                targetTenantId,
                HomeBootstrapService.TENANT_PROJECT_NAME,
                CATALOG_PATH);
        if (targetDoc.isPresent()) {
            return;
        }
        String yamlText = documentService.readContent(sourceDoc.get());
        if (StringUtils.isBlank(yamlText)) {
            return;
        }
        try {
            documentService.createText(
                    targetTenantId,
                    HomeBootstrapService.TENANT_PROJECT_NAME,
                    CATALOG_PATH,
                    "Project Kits",
                    null,
                    yamlText,
                    null);
            log.info("Seeded project-kits catalog into tenantId='{}' from system tenant",
                    targetTenantId);
        } catch (RuntimeException e) {
            // Target _vance project may not exist yet — that's expected
            // during the early tenant-create window, silently skip and
            // let a later call pick it up.
            log.debug("Skipping catalog seed for tenantId='{}': {}",
                    targetTenantId, e.getMessage());
        }
    }

    // ──────────────────── validation ────────────────────

    static void validate(ProjectKitsCatalogDto catalog) {
        if (catalog.getVersion() != CURRENT_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported catalog version: " + catalog.getVersion()
                            + " (expected " + CURRENT_VERSION + ")");
        }
        List<ProjectKitEntry> kits = catalog.getKits();
        if (kits == null) return;
        Set<String> seen = new HashSet<>();
        for (ProjectKitEntry entry : kits) {
            if (entry == null) {
                throw new IllegalArgumentException("Catalog contains a null entry");
            }
            if (StringUtils.isBlank(entry.getName())) {
                throw new IllegalArgumentException("Catalog entry has blank name");
            }
            if (StringUtils.isBlank(entry.getTitle())) {
                throw new IllegalArgumentException(
                        "Catalog entry '" + entry.getName() + "' has blank title");
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

    private ProjectKitsCatalogDto parse(String yamlText, String tenantId) {
        Object loaded = new Yaml().load(yamlText);
        if (loaded == null) {
            return ProjectKitsCatalogDto.builder()
                    .version(CURRENT_VERSION)
                    .kits(new ArrayList<>())
                    .build();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "Catalog at " + tenantId + "/" + HomeBootstrapService.TENANT_PROJECT_NAME
                            + "/" + CATALOG_PATH + " is not a YAML map");
        }
        int version = readInt(map, "version", CURRENT_VERSION);
        List<ProjectKitEntry> entries = new ArrayList<>();
        Object kitsNode = map.get("kits");
        if (kitsNode instanceof List<?> list) {
            for (Object node : list) {
                if (node instanceof Map<?, ?> entryMap) {
                    entries.add(readEntry(entryMap));
                }
            }
        }
        ProjectKitsCatalogDto dto = ProjectKitsCatalogDto.builder()
                .version(version)
                .kits(entries)
                .build();
        // Don't throw on stale on-disk data: we still want to read what's
        // there. Validation only kicks in on save().
        return dto;
    }

    private ProjectKitEntry readEntry(Map<?, ?> entryMap) {
        String name = readString(entryMap, "name");
        String title = readString(entryMap, "title");
        String description = readNullableString(entryMap, "description");
        KitInheritDto source = readSource(entryMap.get("source"));
        return ProjectKitEntry.builder()
                .name(name)
                .title(title)
                .description(description)
                .source(source)
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

    String serialize(ProjectKitsCatalogDto catalog) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", catalog.getVersion());
        List<Map<String, Object>> kits = new ArrayList<>();
        for (ProjectKitEntry entry : catalog.getKits()) {
            Map<String, Object> entryMap = new LinkedHashMap<>();
            entryMap.put("name", entry.getName());
            entryMap.put("title", entry.getTitle());
            if (!StringUtils.isBlank(entry.getDescription())) {
                entryMap.put("description", entry.getDescription());
            }
            KitInheritDto src = entry.getSource();
            if (src != null) {
                Map<String, Object> srcMap = new LinkedHashMap<>();
                srcMap.put("url", src.getUrl());
                if (!StringUtils.isBlank(src.getPath())) srcMap.put("path", src.getPath());
                if (!StringUtils.isBlank(src.getBranch())) srcMap.put("branch", src.getBranch());
                if (!StringUtils.isBlank(src.getCommit())) srcMap.put("commit", src.getCommit());
                entryMap.put("source", srcMap);
            }
            kits.add(entryMap);
        }
        root.put("kits", kits);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(root);
    }
}
