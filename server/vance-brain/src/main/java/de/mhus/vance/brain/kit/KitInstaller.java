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
import de.mhus.vance.brain.servertool.ServerToolRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
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
    public static final String MANIFEST_PATH = "_vance/kit-manifest.yaml";
    public static final String SETTING_FILE_SUFFIX = ".yaml";

    private final DocumentService documentService;
    private final SettingService settingService;
    private final ServerToolRegistry serverToolRegistry;

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

        // ── Tools were once a kit-level concept; they now live as documents
        // under `documents/server-tools/<name>.yaml`. Existing kits that still
        // ship a `tools/` directory must be migrated to that layout.
        result.toolsAdded(new ArrayList<>())
                .toolsUpdated(new ArrayList<>())
                .toolsRemoved(new ArrayList<>());

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
            Map<String, KitYamlMapper.ParsedSetting> settings) {} // key → parsed

    private BuildTreeScan scanBuildTree(Path buildRoot) {
        Map<String, String> documents = new LinkedHashMap<>();
        Map<String, KitYamlMapper.ParsedSetting> settings = new LinkedHashMap<>();

        Path docsRoot = buildRoot.resolve(DOCUMENTS_DIR);
        if (Files.isDirectory(docsRoot)) {
            for (Path file : KitTree.walkNoSymlinks(docsRoot)) {
                if (!Files.isRegularFile(file)) continue;
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
            }
        }

        Path settingsRoot = buildRoot.resolve(SETTINGS_DIR);
        if (Files.isDirectory(settingsRoot)) {
            for (Path file : KitTree.listNoSymlinks(settingsRoot)) {
                if (!Files.isRegularFile(file)) continue;
                String filename = file.getFileName().toString();
                if (!filename.endsWith(SETTING_FILE_SUFFIX)) continue;
                String key = filename.substring(0, filename.length() - SETTING_FILE_SUFFIX.length());
                try {
                    settings.put(key,
                            KitYamlMapper.parseSetting(Files.readString(file), filename));
                } catch (IOException e) {
                    throw new KitException("failed to read " + file, e);
                }
            }
        }
        return new BuildTreeScan(documents, settings);
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
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, String> e : scan.documents().entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            UpsertOutcome outcome = upsertDocument(tenantId, projectId, path, content, actor);
            switch (outcome) {
                case CREATED -> added.add(path);
                case UPDATED -> updated.add(path);
                case SKIPPED -> skipped.add(path);
            }
        }

        List<String> removed = new ArrayList<>();
        if (mode != KitImportMode.APPLY && prune) {
            Set<String> nowKnown = scan.documents().keySet();
            for (String oldPath : previousPaths) {
                if (nowKnown.contains(oldPath)) continue;
                Optional<DocumentDocument> doc =
                        documentService.findByPath(tenantId, projectId, oldPath);
                if (doc.isEmpty()) continue;
                try {
                    documentService.delete(doc.get().getId(), DocumentService.KIT_IDENTITY,
                            de.mhus.vance.shared.permission.WriteActor.SYSTEM);
                    removed.add(oldPath);
                } catch (DocumentService.DocumentLockedException ex) {
                    log.info("KitInstaller: skipped prune of KIT-locked document '{}/{}/{}' lockedFor={}",
                            tenantId, projectId, oldPath, ex.getLockedFor());
                    skipped.add(oldPath);
                }
            }
        }
        result.documentsAdded(added)
                .documentsUpdated(updated)
                .documentsRemoved(removed)
                .documentsSkipped(skipped);

        refreshAffectedToolEntries(tenantId, projectId, added, updated, removed);
    }

    /**
     * Documents land in {@code server-tools/<name>.yaml} carry tool
     * configs. After writing, the {@code ServerToolRegistry} needs to
     * pick the changes up — bootstrap caches per project don't notice
     * direct document writes on their own.
     */
    private void refreshAffectedToolEntries(
            String tenantId, String projectId,
            List<String> added, List<String> updated, List<String> removed) {
        for (String path : added) refreshOneIfTool(tenantId, projectId, path);
        for (String path : updated) refreshOneIfTool(tenantId, projectId, path);
        for (String path : removed) refreshOneIfTool(tenantId, projectId, path);
    }

    private void refreshOneIfTool(String tenantId, String projectId, String path) {
        if (!path.startsWith(ServerToolLoader.SERVER_TOOL_PATH_PREFIX)) return;
        if (!path.endsWith(ServerToolLoader.SERVER_TOOL_PATH_SUFFIX)) return;
        String name = path.substring(
                ServerToolLoader.SERVER_TOOL_PATH_PREFIX.length(),
                path.length() - ServerToolLoader.SERVER_TOOL_PATH_SUFFIX.length());
        if (name.isBlank()) return;
        try {
            serverToolRegistry.refreshOne(tenantId, projectId, name);
        } catch (RuntimeException ex) {
            log.warn("KitInstaller: failed to refresh server-tool '{}/{}/{}': {}",
                    tenantId, projectId, name, ex.toString());
        }
    }

    /**
     * Upsert a document during kit-apply. New documents are created
     * unconditionally; existing documents that are {@code KIT}-locked
     * are skipped with a log entry, and the skip is reported back to
     * the caller so the operation report can list the frozen paths.
     * Other lock levels (AI, USER) do not block Kit-Apply — they are
     * about user-driven / LLM-driven writes, not the kit-update path.
     *
     * @return {@code UpsertOutcome.CREATED} on first apply,
     *         {@code UPDATED} on successful update, {@code SKIPPED} when
     *         the document is frozen against Kit-Apply (KIT lock).
     */
    private UpsertOutcome upsertDocument(
            String tenantId, String projectId, String path, String content,
            @Nullable String actor) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isEmpty()) {
            documentService.createText(tenantId, projectId, path, null, null, content, actor,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
            return UpsertOutcome.CREATED;
        }
        DocumentDocument doc = existing.get();
        if (documentService.readContent(doc) != null) {
            try {
                documentService.update(doc.getId(),
                        /*title*/ null, /*tags*/ null, /*inlineText*/ content,
                        /*newPath*/ null, /*autoSummary*/ null,
                        /*summaryDirty*/ null, /*ragEnabled*/ null,
                        /*mimeType*/ null, DocumentService.KIT_IDENTITY,
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM);
                return UpsertOutcome.UPDATED;
            } catch (DocumentService.DocumentLockedException e) {
                log.info("KitInstaller: skipped KIT-locked document '{}/{}/{}' lockedFor={}",
                        tenantId, projectId, path, e.getLockedFor());
                return UpsertOutcome.SKIPPED;
            } catch (IllegalArgumentException e) {
                log.debug("inline update rejected for {} — falling back to recreate: {}",
                        path, e.getMessage());
            }
        }
        try {
            documentService.delete(doc.getId(), DocumentService.KIT_IDENTITY,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        } catch (DocumentService.DocumentLockedException e) {
            log.info("KitInstaller: skipped KIT-locked document '{}/{}/{}' lockedFor={}",
                    tenantId, projectId, path, e.getLockedFor());
            return UpsertOutcome.SKIPPED;
        }
        documentService.createText(tenantId, projectId, path, null, null, content, actor);
        return UpsertOutcome.UPDATED;
    }

    /** Result of {@link #upsertDocument} — drives the per-path counters in the op report. */
    private enum UpsertOutcome { CREATED, UPDATED, SKIPPED }

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

    // ──────────────────── manifest ────────────────────

    private @Nullable KitManifestDto readManifest(String tenantId, String projectId) {
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectId, MANIFEST_PATH);
        if (doc.isEmpty()) return null;
        DocumentDocument d = doc.get();
        String content = documentService.readContent(d);
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
                    "Kit Manifest", List.of("vance", "kit"), yaml, actor,
                    de.mhus.vance.shared.permission.WriteActor.SYSTEM);
            return;
        }
        DocumentDocument doc = existing.get();
        if (documentService.readContent(doc) != null) {
            try {
                documentService.update(doc.getId(),
                        /*title*/ null, /*tags*/ null, /*inlineText*/ yaml,
                        /*newPath*/ null, /*autoSummary*/ null,
                        /*summaryDirty*/ null, /*ragEnabled*/ null,
                        /*mimeType*/ null, DocumentService.KIT_IDENTITY,
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM);
                return;
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        documentService.delete(doc.getId(), DocumentService.KIT_IDENTITY,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
        documentService.createText(tenantId, projectId, MANIFEST_PATH,
                "Kit Manifest", List.of("vance", "kit"), yaml, actor,
                de.mhus.vance.shared.permission.WriteActor.SYSTEM);
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
                .ifPresent(doc -> documentService.delete(doc.getId(),
                        DocumentService.KIT_IDENTITY,
                        de.mhus.vance.shared.permission.WriteActor.SYSTEM));
    }

    /** Set-based union helper used by tests. */
    static Set<String> unionKeys(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
