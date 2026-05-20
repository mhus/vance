package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.KitWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

/**
 * Clones a tool-templates source repo, scans its {@code tools/}
 * subdirectory, and returns a fresh {@link ToolTemplateCatalogDto}.
 * Mirrors {@link KitCatalogScanService} for project-kits — same flow
 * (clone → walk subdirs → return DTO without persisting), but recognises
 * sibling {@code template.yaml} files instead of {@code kit.yaml} as the
 * marker.
 *
 * <p>Convention of the source repo: each direct sub-directory of
 * {@code tools/} that carries a {@code template.yaml} becomes one
 * catalog entry. Title and description come from the top-level
 * {@code name}, {@code title}, {@code description} fields of the
 * {@code template.yaml}; missing values fall back to the directory
 * name. The optional file {@code tools/catalog.yaml} carries per-entry
 * overrides ({@code name}, {@code title}, {@code description},
 * {@code category}).
 *
 * <p>See {@code planning/tool-templates.md} §4½.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolTemplateCatalogScanService {

    /** Default tool-templates source repo when {@code --git} is omitted. */
    public static final String DEFAULT_REPO = "https://github.com/mhus/vance-kits.git";

    /** Default branch used when the caller does not specify a ref. */
    public static final String DEFAULT_REF = "main";

    /** Subdirectory inside the repo holding tool-template kits. */
    public static final String TOOLS_SUBDIR = "tools";

    /** Optional override file inside {@link #TOOLS_SUBDIR}. */
    public static final String CATALOG_OVERRIDES_FILE = "catalog.yaml";

    /** Marker file that distinguishes a tool-template kit from a plain kit. */
    public static final String TEMPLATE_MARKER = "template.yaml";

    private final KitWorkspace workspace;

    /**
     * Clone {@code gitUrl} at {@code ref} and return the resulting
     * catalog. {@code token} (optional) is used as the HTTPS bearer for
     * private repos — never persisted.
     */
    public ToolTemplateCatalogDto scan(
            String gitUrl, @Nullable String ref, @Nullable String token) {
        if (StringUtils.isBlank(gitUrl)) {
            throw new KitException("scan gitUrl must not be blank");
        }
        String effectiveRef = StringUtils.isBlank(ref) ? DEFAULT_REF : ref;
        Path tmp = workspace.allocate("tool-templates-scan");
        try {
            cloneRepo(gitUrl, effectiveRef, token, tmp);
            return buildCatalog(tmp, gitUrl, effectiveRef);
        } finally {
            workspace.remove(tmp);
        }
    }

    // ──────────────────── clone ────────────────────

    private void cloneRepo(String gitUrl, String ref, @Nullable String token, Path target) {
        try (Git ignored = Git.cloneRepository()
                .setURI(gitUrl)
                .setDirectory(target.toFile())
                .setBranch(ref)
                .setCredentialsProvider(credentials(token))
                .call()) {
            log.debug("Cloned {} at ref '{}' into {}", gitUrl, ref, target);
        } catch (GitAPIException e) {
            throw new KitException(
                    "git clone failed for " + gitUrl + " (ref=" + ref + "): " + e.getMessage(), e);
        }
    }

    private static @Nullable UsernamePasswordCredentialsProvider credentials(
            @Nullable String token) {
        if (StringUtils.isBlank(token)) return null;
        return new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    // ──────────────────── catalog build ────────────────────

    private ToolTemplateCatalogDto buildCatalog(Path repoRoot, String gitUrl, String ref) {
        Path toolsDir = repoRoot.resolve(TOOLS_SUBDIR);
        if (!Files.isDirectory(toolsDir)) {
            throw new KitException("repo does not contain a '" + TOOLS_SUBDIR + "/' directory");
        }

        Map<String, OverrideEntry> overrides = loadOverrides(toolsDir);

        List<ToolTemplateCatalogEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(toolsDir)) {
            children
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(subDir -> {
                        Path templateFile = subDir.resolve(TEMPLATE_MARKER);
                        if (!Files.isRegularFile(templateFile)) {
                            log.debug("Skipping {} — no {}", subDir, TEMPLATE_MARKER);
                            return;
                        }
                        String subName = subDir.getFileName().toString();
                        TemplateHeader header = readTemplateHeader(templateFile, subName);
                        OverrideEntry o = overrides.get(subName);
                        String name = pickFirst(o == null ? null : o.name, header.name, subName);
                        String title = pickFirst(o == null ? null : o.title, header.title, subName);
                        String description = pickFirst(o == null ? null : o.description, header.description);
                        String category = pickFirst(o == null ? null : o.category, header.category);
                        entries.add(ToolTemplateCatalogEntry.builder()
                                .name(name)
                                .title(title)
                                .description(description)
                                .category(category)
                                .source(KitInheritDto.builder()
                                        .url(gitUrl)
                                        .path(TOOLS_SUBDIR + "/" + subName)
                                        .branch(ref)
                                        .build())
                                .build());
                    });
        } catch (IOException e) {
            throw new KitException("failed to list tools directory: " + e.getMessage(), e);
        }

        return ToolTemplateCatalogDto.builder()
                .version(1)
                .templates(entries)
                .build();
    }

    private TemplateHeader readTemplateHeader(Path templateFile, String fallbackName) {
        try {
            String yamlText = Files.readString(templateFile);
            Object loaded = new Yaml().load(yamlText);
            TemplateHeader h = new TemplateHeader();
            if (loaded instanceof Map<?, ?> root) {
                h.name = readString(root, "name");
                h.title = readString(root, "title");
                h.description = readString(root, "description");
                h.category = readString(root, "category");
            }
            if (StringUtils.isBlank(h.name)) h.name = fallbackName;
            return h;
        } catch (IOException e) {
            throw new KitException(
                    "failed to read " + TEMPLATE_MARKER + " in " + templateFile.getParent()
                            + ": " + e.getMessage(), e);
        }
    }

    private Map<String, OverrideEntry> loadOverrides(Path toolsDir) {
        Path overridesFile = toolsDir.resolve(CATALOG_OVERRIDES_FILE);
        if (!Files.isRegularFile(overridesFile)) {
            return Map.of();
        }
        try {
            String yamlText = Files.readString(overridesFile);
            Object loaded = new Yaml().load(yamlText);
            if (!(loaded instanceof Map<?, ?> root)) {
                return Map.of();
            }
            Object overridesNode = root.get("overrides");
            if (!(overridesNode instanceof Map<?, ?> overridesMap)) {
                return Map.of();
            }
            Map<String, OverrideEntry> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : overridesMap.entrySet()) {
                String subDirName = e.getKey() == null ? null : e.getKey().toString();
                if (subDirName == null) continue;
                if (!(e.getValue() instanceof Map<?, ?> fields)) continue;
                OverrideEntry o = new OverrideEntry();
                o.name = readString(fields, "name");
                o.title = readString(fields, "title");
                o.description = readString(fields, "description");
                o.category = readString(fields, "category");
                result.put(subDirName, o);
            }
            return result;
        } catch (IOException e) {
            throw new KitException(
                    "failed to read " + CATALOG_OVERRIDES_FILE + ": " + e.getMessage(), e);
        }
    }

    private static @Nullable String readString(Map<?, ?> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static @Nullable String pickFirst(@Nullable String... candidates) {
        for (String c : candidates) {
            if (!StringUtils.isBlank(c)) return c;
        }
        return null;
    }

    private static final class TemplateHeader {
        @Nullable String name;
        @Nullable String title;
        @Nullable String description;
        @Nullable String category;
    }

    private static final class OverrideEntry {
        @Nullable String name;
        @Nullable String title;
        @Nullable String description;
        @Nullable String category;
    }
}
