package de.mhus.vance.brain.kit.catalog;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
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
 * Clones a kit catalog repo into a tmp workspace, scans its
 * {@code kits/} subdir for kit sources, and returns a fresh
 * {@link ProjectKitsCatalogDto}. The service does not persist anything
 * — anus (the only client) loads, diffs and saves separately.
 *
 * <p>Convention of the source repo: each direct sub-directory of
 * {@code kits/} that contains a {@code kit.yaml} becomes one catalog
 * entry. The optional file {@code kits/catalog.yaml} carries per-kit
 * overrides (catalog name, title, description). Without overrides the
 * subdirectory name is used as both catalog {@code name} and
 * {@code title}.
 *
 * <p>Spec: {@code specification/project-kits-catalog.md} §7.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitCatalogScanService {

    /** Default kit-catalog repo URL (overridable per scan). */
    public static final String DEFAULT_KIT_REPO =
            "https://github.com/mhus/vance-kits.git";

    /** Default branch used when the caller does not specify a ref. */
    public static final String DEFAULT_REF = "main";

    /** Subdirectory inside the repo holding kit sources. */
    public static final String KITS_SUBDIR = "kits";

    /** Optional override file inside {@link #KITS_SUBDIR}. */
    public static final String CATALOG_OVERRIDES_FILE = "catalog.yaml";

    private final KitWorkspace workspace;

    /**
     * Scan {@code gitUrl} at {@code ref} and return the resulting
     * catalog. {@code token} (optional) is used as the HTTPS bearer
     * for private repos — never persisted.
     */
    public ProjectKitsCatalogDto scan(
            String gitUrl, @Nullable String ref, @Nullable String token) {
        if (StringUtils.isBlank(gitUrl)) {
            throw new KitException("scan gitUrl must not be blank");
        }
        String effectiveRef = StringUtils.isBlank(ref) ? DEFAULT_REF : ref;
        Path tmp = workspace.allocate("catalog-scan");
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

    private ProjectKitsCatalogDto buildCatalog(Path repoRoot, String gitUrl, String ref) {
        Path kitsDir = repoRoot.resolve(KITS_SUBDIR);
        if (!Files.isDirectory(kitsDir)) {
            throw new KitException("repo does not contain a '" + KITS_SUBDIR + "/' directory");
        }

        Map<String, OverrideEntry> overrides = loadOverrides(kitsDir);

        List<ProjectKitEntry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(kitsDir)) {
            children
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(subDir -> {
                        if (!Files.isRegularFile(subDir.resolve("kit.yaml"))) {
                            log.debug("Skipping {} — no kit.yaml", subDir);
                            return;
                        }
                        String subName = subDir.getFileName().toString();
                        OverrideEntry o = overrides.get(subName);
                        String name = o != null && !StringUtils.isBlank(o.name) ? o.name : subName;
                        String title = o != null && !StringUtils.isBlank(o.title) ? o.title : subName;
                        String description = o == null ? null : o.description;
                        entries.add(ProjectKitEntry.builder()
                                .name(name)
                                .title(title)
                                .description(description)
                                .source(KitInheritDto.builder()
                                        .url(gitUrl)
                                        .path(KITS_SUBDIR + "/" + subName)
                                        .branch(ref)
                                        .build())
                                .build());
                    });
        } catch (IOException e) {
            throw new KitException("failed to list kits directory: " + e.getMessage(), e);
        }

        return ProjectKitsCatalogDto.builder()
                .version(1)
                .kits(entries)
                .build();
    }

    private Map<String, OverrideEntry> loadOverrides(Path kitsDir) {
        Path overridesFile = kitsDir.resolve(CATALOG_OVERRIDES_FILE);
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

    private static final class OverrideEntry {
        @Nullable String name;
        @Nullable String title;
        @Nullable String description;
    }
}
