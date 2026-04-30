package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.InheritArtefactsDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.servertool.ServerToolService;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Persists a resolved kit into the project — documents go through
 * {@link DocumentService}, settings through {@link SettingService},
 * tools through {@link ServerToolService}. Computes the diff against
 * the previous manifest (when one exists) and writes a fresh
 * {@code _vance/kit-manifest.yaml}.
 *
 * <p>Three modes:
 *
 * <ul>
 *   <li>{@link KitImportMode#INSTALL} / {@link KitImportMode#UPDATE} —
 *       writes a manifest. {@code prune} extra-deletes artefacts that
 *       were tracked in the previous manifest but are absent in the
 *       new kit.</li>
 *   <li>{@link KitImportMode#APPLY} — overwrites silently, no
 *       manifest. {@code keepPasswords} preserves existing
 *       PASSWORD-settings.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitInstaller {

    public static final String DOCUMENTS_DIR = "documents";
    public static final String SETTINGS_DIR = "settings";
    public static final String TOOLS_DIR = "tools";
    public static final String MANIFEST_PATH = "_vance/kit-manifest.yaml";
    public static final String SETTING_FILE_SUFFIX = ".yaml";
    public static final String TOOL_FILE_SUFFIX = ".tool.yaml";

    private final DocumentService documentService;
    private final SettingService settingService;
    private final ServerToolService serverToolService;

    public KitOperationResultDto apply(
            String tenantId,
            String projectId,
            KitInheritDto source,
            KitResolver.ResolvedKit resolved,
            KitImportMode mode,
            boolean prune,
            boolean keepPasswords,
            @Nullable String vaultPassword,
            @Nullable String actor) {

        KitDescriptorDto top = resolved.topLayer();
        KitOperationResultDto.KitOperationResultDtoBuilder result =
                KitOperationResultDto.builder()
                        .kitName(top.getName())
                        .mode(mode.name())
                        .sourceCommit(resolved.sourceCommit())
                        .inheritedKits(new ArrayList<>(resolved.resolvedInherits()))
                        .warnings(new ArrayList<>(resolved.warnings()));

        // ── Read previous manifest (only relevant for INSTALL/UPDATE).
        @Nullable KitManifestDto previous = mode == KitImportMode.APPLY
                ? null
                : readManifest(tenantId, projectId);

        // ── Scan build tree.
        BuildTreeScan scan = scanBuildTree(resolved.buildRoot());

        // ── Documents.
        applyDocuments(tenantId, projectId, scan, previous, mode, prune, actor, result);

        // ── Settings.
        applySettings(tenantId, projectId, scan, previous, mode,
                prune, keepPasswords, vaultPassword, top.isHasEncryptedSecrets(), result);

        // ── Tools.
        applyTools(tenantId, projectId, scan, previous, mode, prune, result);

        // ── Manifest.
        if (mode != KitImportMode.APPLY) {
            KitManifestDto manifest = buildManifest(top, source, resolved, scan, actor);
            writeManifest(tenantId, projectId, manifest, actor);
        }
        return result.build();
    }

    // ──────────────────── scan ────────────────────

    private record BuildTreeScan(
            Map<String, String> documents,           // path → content
            Map<String, KitYamlMapper.ParsedSetting> settings, // key → parsed
            Map<String, Map<String, Object>> tools) {}        // name → raw tool map

    private BuildTreeScan scanBuildTree(Path buildRoot) {
        Map<String, String> documents = new LinkedHashMap<>();
        Map<String, KitYamlMapper.ParsedSetting> settings = new LinkedHashMap<>();
        Map<String, Map<String, Object>> tools = new LinkedHashMap<>();

        Path docsRoot = buildRoot.resolve(DOCUMENTS_DIR);
        if (Files.isDirectory(docsRoot)) {
            try (Stream<Path> stream = Files.walk(docsRoot, FileVisitOption.FOLLOW_LINKS)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    String rel = docsRoot.relativize(file).toString().replace('\\', '/');
                    if (rel.equals(MANIFEST_PATH)) {
                        throw new KitException("kits must not ship " + MANIFEST_PATH
                                + " — it is generated by the installer");
                    }
                    try {
                        documents.put(rel, Files.readString(file));
                    } catch (IOException e) {
                        throw new KitException("failed to read " + file, e);
                    }
                });
            } catch (IOException e) {
                throw new KitException("failed to walk " + docsRoot, e);
            }
        }

        Path settingsRoot = buildRoot.resolve(SETTINGS_DIR);
        if (Files.isDirectory(settingsRoot)) {
            try (Stream<Path> stream = Files.list(settingsRoot)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    String filename = file.getFileName().toString();
                    if (!filename.endsWith(SETTING_FILE_SUFFIX)) return;
                    String key = filename.substring(0, filename.length() - SETTING_FILE_SUFFIX.length());
                    try {
                        settings.put(key,
                                KitYamlMapper.parseSetting(Files.readString(file), filename));
                    } catch (IOException e) {
                        throw new KitException("failed to read " + file, e);
                    }
                });
            } catch (IOException e) {
                throw new KitException("failed to list " + settingsRoot, e);
            }
        }

        Path toolsRoot = buildRoot.resolve(TOOLS_DIR);
        if (Files.isDirectory(toolsRoot)) {
            try (Stream<Path> stream = Files.list(toolsRoot)) {
                stream.filter(Files::isRegularFile).forEach(file -> {
                    String filename = file.getFileName().toString();
                    if (!filename.endsWith(TOOL_FILE_SUFFIX)) return;
                    String name = filename.substring(0, filename.length() - TOOL_FILE_SUFFIX.length());
                    try {
                        tools.put(name,
                                KitYamlMapper.parseToolMap(Files.readString(file), filename));
                    } catch (IOException e) {
                        throw new KitException("failed to read " + file, e);
                    }
                });
            } catch (IOException e) {
                throw new KitException("failed to list " + toolsRoot, e);
            }
        }
        return new BuildTreeScan(documents, settings, tools);
    }

    // ──────────────────── documents ────────────────────

    private void applyDocuments(
            String tenantId, String projectId, BuildTreeScan scan,
            @Nullable KitManifestDto previous, KitImportMode mode, boolean prune,
            @Nullable String actor, KitOperationResultDto.KitOperationResultDtoBuilder result) {

        Set<String> previousPaths = unionAcrossLayers(previous,
                KitManifestDto::getDocuments,
                InheritArtefactsDto::getDocuments);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        for (Map.Entry<String, String> e : scan.documents().entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            boolean existed = upsertDocument(tenantId, projectId, path, content, actor);
            if (existed) updated.add(path);
            else added.add(path);
        }

        List<String> removed = new ArrayList<>();
        if (mode != KitImportMode.APPLY && prune) {
            Set<String> nowKnown = scan.documents().keySet();
            for (String oldPath : previousPaths) {
                if (nowKnown.contains(oldPath)) continue;
                Optional<DocumentDocument> doc =
                        documentService.findByPath(tenantId, projectId, oldPath);
                doc.ifPresent(d -> documentService.delete(d.getId()));
                removed.add(oldPath);
            }
        }
        result.documentsAdded(added).documentsUpdated(updated).documentsRemoved(removed);
    }

    private boolean upsertDocument(
            String tenantId, String projectId, String path, String content,
            @Nullable String actor) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isEmpty()) {
            documentService.createText(tenantId, projectId, path, null, null, content, actor);
            return false;
        }
        DocumentDocument doc = existing.get();
        if (doc.getInlineText() != null) {
            try {
                documentService.update(doc.getId(), null, null, content, null);
                return true;
            } catch (IllegalArgumentException e) {
                log.debug("inline update rejected for {} — falling back to recreate: {}",
                        path, e.getMessage());
            }
        }
        documentService.delete(doc.getId());
        documentService.createText(tenantId, projectId, path, null, null, content, actor);
        return true;
    }

    // ──────────────────── settings ────────────────────

    private void applySettings(
            String tenantId, String projectId, BuildTreeScan scan,
            @Nullable KitManifestDto previous, KitImportMode mode,
            boolean prune, boolean keepPasswords,
            @Nullable String vaultPassword, boolean kitDeclaresEncrypted,
            KitOperationResultDto.KitOperationResultDtoBuilder result) {

        Set<String> previousKeys = unionAcrossLayers(previous,
                KitManifestDto::getSettings,
                InheritArtefactsDto::getSettings);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> skippedPw = new ArrayList<>();
        boolean haveWarnedAboutMissingVault = false;

        for (Map.Entry<String, KitYamlMapper.ParsedSetting> entry : scan.settings().entrySet()) {
            String key = entry.getKey();
            KitYamlMapper.ParsedSetting parsed = entry.getValue();
            boolean existed = settingService.exists(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);

            if (parsed.type() == SettingType.PASSWORD) {
                if (mode == KitImportMode.APPLY && keepPasswords) {
                    log.debug("Skipping password '{}' due to --keep-passwords", key);
                    continue;
                }
                if (vaultPassword == null || vaultPassword.isBlank()) {
                    if (!haveWarnedAboutMissingVault && kitDeclaresEncrypted) {
                        result.warnings(addWarning(result.build().getWarnings(),
                                "vault password not provided — PASSWORD settings skipped"));
                        haveWarnedAboutMissingVault = true;
                    }
                    skippedPw.add(key);
                    continue;
                }
                boolean ok = settingService.encryptFromImport(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key,
                        vaultPassword, parsed.value());
                if (!ok) {
                    skippedPw.add(key);
                    continue;
                }
            } else {
                settingService.set(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key,
                        parsed.value(), parsed.type(), parsed.description());
            }
            if (existed) updated.add(key);
            else added.add(key);
        }

        List<String> removed = new ArrayList<>();
        if (mode != KitImportMode.APPLY && prune) {
            Set<String> nowKnown = scan.settings().keySet();
            for (String oldKey : previousKeys) {
                if (nowKnown.contains(oldKey)) continue;
                settingService.delete(tenantId, SettingService.SCOPE_PROJECT, projectId, oldKey);
                removed.add(oldKey);
            }
        }
        result.settingsAdded(added).settingsUpdated(updated).settingsRemoved(removed);
        result.skippedPasswords(skippedPw);
    }

    private static List<String> addWarning(List<String> existing, String warning) {
        List<String> out = new ArrayList<>(existing);
        out.add(warning);
        return out;
    }

    // ──────────────────── tools ────────────────────

    private void applyTools(
            String tenantId, String projectId, BuildTreeScan scan,
            @Nullable KitManifestDto previous, KitImportMode mode, boolean prune,
            KitOperationResultDto.KitOperationResultDtoBuilder result) {

        Set<String> previousNames = unionAcrossLayers(previous,
                KitManifestDto::getTools,
                InheritArtefactsDto::getTools);

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();

        for (Map.Entry<String, Map<String, Object>> entry : scan.tools().entrySet()) {
            String name = entry.getKey();
            Map<String, Object> spec = entry.getValue();
            ServerToolDocument doc = toolFromSpec(name, spec);
            Optional<ServerToolDocument> existing =
                    serverToolService.findDocument(tenantId, projectId, name);
            if (existing.isPresent()) {
                serverToolService.update(tenantId, projectId, name, doc);
                updated.add(name);
            } else {
                serverToolService.create(tenantId, projectId, doc);
                added.add(name);
            }
        }

        List<String> removed = new ArrayList<>();
        if (mode != KitImportMode.APPLY && prune) {
            Set<String> nowKnown = scan.tools().keySet();
            for (String oldName : previousNames) {
                if (nowKnown.contains(oldName)) continue;
                serverToolService.delete(tenantId, projectId, oldName);
                removed.add(oldName);
            }
        }
        result.toolsAdded(added).toolsUpdated(updated).toolsRemoved(removed);
    }

    @SuppressWarnings("unchecked")
    private static ServerToolDocument toolFromSpec(String filenameStem, Map<String, Object> spec) {
        String name = stringOr(spec.get("name"), filenameStem);
        String type = require(spec, "type", filenameStem);
        String description = stringOr(spec.get("description"), "");
        Map<String, Object> parameters = new LinkedHashMap<>();
        Object paramsRaw = spec.get("parameters");
        if (paramsRaw instanceof Map<?, ?> m) {
            parameters.putAll((Map<String, Object>) m);
        }
        List<String> labels = new ArrayList<>();
        Object labelsRaw = spec.get("labels");
        if (labelsRaw instanceof List<?> list) {
            for (Object o : list) {
                if (o == null) continue;
                String s = o.toString().trim();
                if (!s.isEmpty()) labels.add(s);
            }
        }
        return ServerToolDocument.builder()
                .name(name)
                .type(type)
                .description(description)
                .parameters(parameters)
                .labels(labels)
                .enabled(boolOrTrue(spec.get("enabled")))
                .primary(boolOrFalse(spec.get("primary")))
                .build();
    }

    private static String stringOr(@Nullable Object v, String fallback) {
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    private static String require(Map<String, Object> spec, String key, String label) {
        Object v = spec.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new KitException("tool " + label + ": missing required field '" + key + "'");
        }
        return v.toString().trim();
    }

    private static boolean boolOrTrue(@Nullable Object v) {
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    private static boolean boolOrFalse(@Nullable Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString().trim());
    }

    // ──────────────────── manifest ────────────────────

    private @Nullable KitManifestDto readManifest(String tenantId, String projectId) {
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectId, MANIFEST_PATH);
        if (doc.isEmpty()) return null;
        DocumentDocument d = doc.get();
        String content = d.getInlineText();
        if (content == null) {
            try (var in = documentService.loadContent(d)) {
                content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new KitException("failed to read manifest at " + MANIFEST_PATH, e);
            }
        }
        try {
            return KitYamlMapper.parseManifest(content);
        } catch (KitException e) {
            log.warn("Existing manifest at {} is malformed: {} — proceeding without diff",
                    MANIFEST_PATH, e.getMessage());
            return null;
        }
    }

    private KitManifestDto buildManifest(
            KitDescriptorDto top, KitInheritDto source,
            KitResolver.ResolvedKit resolved, BuildTreeScan scan, @Nullable String actor) {
        KitResolver.LayerArtefacts topOwned = resolved.topLayerArtefacts();
        List<InheritArtefactsDto> inheritArtefacts = new ArrayList<>();
        for (Map.Entry<String, KitResolver.LayerArtefacts> e
                : resolved.inheritArtefacts().entrySet()) {
            KitResolver.LayerArtefacts a = e.getValue();
            inheritArtefacts.add(InheritArtefactsDto.builder()
                    .name(e.getKey())
                    .documents(new ArrayList<>(a.documents()))
                    .settings(new ArrayList<>(a.settings()))
                    .tools(new ArrayList<>(a.tools()))
                    .build());
        }
        return KitManifestDto.builder()
                .kit(KitMetadataDto.builder()
                        .name(top.getName())
                        .description(top.getDescription())
                        .version(top.getVersion())
                        .build())
                .origin(KitOriginDto.builder()
                        .url(source.getUrl())
                        .path(source.getPath())
                        .branch(source.getBranch())
                        .commit(resolved.sourceCommit())
                        .installedAt(Instant.now())
                        .installedBy(actor)
                        .build())
                .documents(new ArrayList<>(topOwned.documents()))
                .settings(new ArrayList<>(topOwned.settings()))
                .tools(new ArrayList<>(topOwned.tools()))
                .inherits(new ArrayList<>(top.getInherits() == null
                        ? Collections.emptyList() : top.getInherits()))
                .resolvedInherits(new ArrayList<>(resolved.resolvedInherits()))
                .inheritArtefacts(inheritArtefacts)
                .hasEncryptedSecrets(hasAnyPasswordSetting(scan))
                .build();
    }

    private static boolean hasAnyPasswordSetting(BuildTreeScan scan) {
        for (KitYamlMapper.ParsedSetting s : scan.settings().values()) {
            if (s.type() == SettingType.PASSWORD) return true;
        }
        return false;
    }

    private static Set<String> unionAcrossLayers(
            @Nullable KitManifestDto previous,
            java.util.function.Function<KitManifestDto, List<String>> topGetter,
            java.util.function.Function<InheritArtefactsDto, List<String>> inheritGetter) {
        if (previous == null) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        List<String> top = topGetter.apply(previous);
        if (top != null) out.addAll(top);
        if (previous.getInheritArtefacts() != null) {
            for (InheritArtefactsDto i : previous.getInheritArtefacts()) {
                List<String> v = inheritGetter.apply(i);
                if (v != null) out.addAll(v);
            }
        }
        return out;
    }

    private void writeManifest(
            String tenantId, String projectId, KitManifestDto manifest,
            @Nullable String actor) {
        String yaml = KitYamlMapper.writeManifest(manifest);
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, MANIFEST_PATH);
        if (existing.isEmpty()) {
            documentService.createText(tenantId, projectId, MANIFEST_PATH,
                    "Kit Manifest", List.of("vance", "kit"), yaml, actor);
            return;
        }
        DocumentDocument doc = existing.get();
        if (doc.getInlineText() != null) {
            try {
                documentService.update(doc.getId(), null, null, yaml, null);
                return;
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        documentService.delete(doc.getId());
        documentService.createText(tenantId, projectId, MANIFEST_PATH,
                "Kit Manifest", List.of("vance", "kit"), yaml, actor);
    }

    /**
     * Reads the current manifest if one exists, returning the parsed
     * DTO or {@code null}. Used by {@link KitService#status} and the
     * exporter.
     */
    public @Nullable KitManifestDto loadManifest(String tenantId, String projectId) {
        return readManifest(tenantId, projectId);
    }

    /** Removes the manifest document (used after a successful uninstall). */
    public void removeManifest(String tenantId, String projectId) {
        documentService.findByPath(tenantId, projectId, MANIFEST_PATH)
                .ifPresent(doc -> documentService.delete(doc.getId()));
    }

    /** Set-based union helper used by tests. */
    static Set<String> unionKeys(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
