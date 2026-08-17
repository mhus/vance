package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.InheritArtefactsDto;
import de.mhus.vance.api.kit.KitArtefactDto;
import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitPolicyAction;
import de.mhus.vance.brain.servertool.ServerToolRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.kit.KitHash;
import de.mhus.vance.shared.kit.KitTree;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.servertool.ServerToolLoader;
import de.mhus.vance.shared.settings.AgentSettingKeyPolicy;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
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
 * Persists a resolved kit into the project — documents through
 * {@link DocumentService}, settings through {@link SettingService} —
 * and records what it wrote.
 *
 * <p>Two shapes of operation:
 *
 * <ul>
 *   <li>{@link KitImportMode#INSTALL} / {@link KitImportMode#UPDATE} —
 *       tracked. Writes {@code _vance/kits/installed/<id>.yaml}, honours
 *       the kit's update policy, and can prune artefacts the kit lost in
 *       its new version. A project may carry any number of these.</li>
 *   <li>{@link KitImportMode#APPLY} — untracked splat. Overwrites
 *       unconditionally, writes no record. No policy applies: with no
 *       record there are no hashes to compare against, and a splat that
 *       silently skipped half its files would just be broken.
 *       {@code keepPasswords} still protects existing credentials.</li>
 * </ul>
 *
 * <p>Spec: {@code planning/kit-installed-multi.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitInstaller {

    public static final String DOCUMENTS_DIR = "documents";
    public static final String SETTINGS_DIR = "settings";
    public static final String SETTING_FILE_SUFFIX = ".yaml";

    /**
     * Where a conflicting merge lands. Deliberately a sibling document
     * rather than an overwritten original: the user's version is the one
     * thing an update must never destroy.
     *
     * <p>The sidecar belongs to nobody — it is not recorded as an
     * artefact and therefore never pruned. Cleaning it up is the user's
     * job, and its presence is the reminder.
     */
    public static final String MERGE_SIDECAR_SUFFIX = ".kit-merge";

    private final DocumentService documentService;
    private final SettingService settingService;
    private final ServerToolRegistry serverToolRegistry;
    private final AgentSettingKeyPolicy agentKeyPolicy;
    private final KitRecordStore recordStore;
    /** Only for {@link KitBaseTree} — re-resolving the previously installed state. */
    private final KitResolver resolver;
    private final KitWorkspace workspace;

    public KitOperationResultDto apply(
            KitAccess access,
            String projectId,
            KitInheritDto source,
            KitResolver.ResolvedKit resolved,
            KitImportMode mode,
            boolean prune,
            boolean keepPasswords,
            @Nullable String vaultPassword,
            boolean writeManifest,
            SettingWriteOrigin origin,
            @Nullable String actor) {

        String tenantId = access.tenantId();

        KitDescriptorDto top = resolved.topLayer();
        boolean tracked = mode != KitImportMode.APPLY;

        // The record of the previous install of *this* kit — found by source
        // coordinates, not by the derived id, so a kit that renames itself
        // still updates in place instead of forking.
        @Nullable KitInstalledRecordDto previous = tracked
                ? recordStore.findByOrigin(tenantId, projectId, source.getUrl(), source.getPath())
                : null;
        // Keep the id the first install minted; only a genuinely new kit gets
        // a fresh one. Renaming changes the label, never the identity.
        String recordId = previous != null
                ? previous.getId()
                : KitRecordId.of(top.getName(), source);

        KitOperationResultDto.KitOperationResultDtoBuilder result =
                KitOperationResultDto.builder()
                        .kitName(top.getName())
                        .kitId(tracked ? recordId : null)
                        .mode(mode.name())
                        .sourceCommit(resolved.sourceCommit())
                        .inheritedKits(new ArrayList<>(resolved.resolvedInherits()))
                        .warnings(new ArrayList<>(resolved.warnings()));

        // Policy cascade: the user's config document wins, the kit author's
        // suggestion fills in when there is none, and keep-with-no-exceptions
        // is the floor. The suggestion is deliberately not materialised —
        // that way a later kit version can improve its recommendation for as
        // long as the user has not overridden it.
        KitPolicy policy = tracked
                ? KitPolicy.of(recordStore.loadConfig(tenantId, projectId, recordId)
                        .getPolicy(), top.getPolicy())
                : KitPolicy.defaults();

        BuildTreeScan scan = scanBuildTree(resolved.buildRoot());
        Ownership ownership = Ownership.from(resolved, top.getName());
        SiblingHashes siblings = tracked
                ? SiblingHashes.of(recordStore.list(tenantId, projectId), recordId)
                : SiblingHashes.empty();

        // Only materialised if some artefact actually needs a common
        // ancestor — see KitBaseTree.
        KitBaseTree baseTree = tracked && previous != null
                ? new KitBaseTree(resolver, workspace, access, source, previous.getOrigin())
                : null;
        try {
            List<KitArtefactDto> documentArtefacts = applyDocuments(
                    tenantId, projectId, scan, previous, policy, ownership, top.getName(),
                    tracked, baseTree, siblings, actor, result);
            List<KitArtefactDto> settingArtefacts = applySettings(
                    tenantId, projectId, scan, previous, policy, ownership, top.getName(), mode,
                    keepPasswords, vaultPassword, top.isHasEncryptedSecrets(), siblings,
                    origin, result);

            if (tracked) {
                pruneLostArtefacts(tenantId, projectId, recordId, previous, scan, prune, result);
            }

            // Server-tool configs are ordinary documents; the kit layer does not
            // know the concept. The lists stay for API compatibility.
            result.toolsAdded(new ArrayList<>())
                    .toolsUpdated(new ArrayList<>())
                    .toolsRemoved(new ArrayList<>());

            if (tracked) {
                recordStore.save(tenantId, projectId,
                        buildRecord(recordId, top, source, resolved,
                                documentArtefacts, settingArtefacts, scan, actor),
                        actor);
            }
        } finally {
            if (baseTree != null) baseTree.cleanup();
        }
        // The authoring manifest is written on explicit request — and refreshed
        // on every later update of the same kit. Being a kit source is a state,
        // not a one-off act: without this, a project would stay the source of
        // whichever version happened to be installed when the box was ticked,
        // and the next export would push a stale tree.
        if (writeManifest || isAuthoringSourceOf(tenantId, projectId, recordId, top)) {
            recordStore.saveManifest(tenantId, projectId,
                    buildManifest(top, source, resolved, scan, actor), actor);
        }
        return result.build();
    }

    /**
     * True when the project already declares itself the source of exactly this
     * kit. Compared over the record identity — {@code (url, path)} — because
     * that is what "the same kit" means everywhere else.
     */
    private boolean isAuthoringSourceOf(
            String tenantId, String projectId, String recordId, KitDescriptorDto top) {
        KitManifestDto existing = recordStore.loadManifest(tenantId, projectId);
        if (existing == null || existing.getOrigin() == null) return false;
        String manifestRecordId = KitRecordId.of(
                existing.getKit().getName(),
                existing.getOrigin().getUrl(),
                existing.getOrigin().getPath());
        return manifestRecordId.equals(recordId);
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
                // A kit that could write here would forge its own install
                // record — grant itself immunity from other kits, rewrite
                // ownership, embellish its descriptor.
                if (KitRecordStore.isReservedPath(rel)) {
                    throw new KitException("kits must not ship documents under "
                            + KitRecordStore.KITS_PREFIX
                            + " — that directory is owned by the kit subsystem"
                            + " (offending path: " + rel + ")");
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

    // ──────────────────── ownership ────────────────────

    /** Which layer of the resolved chain contributed each artefact, after last-writer-wins. */
    private record Ownership(Map<String, String> documents, Map<String, String> settings) {

        static Ownership from(KitResolver.ResolvedKit resolved, String topLayerName) {
            Map<String, String> docs = new LinkedHashMap<>();
            Map<String, String> settings = new LinkedHashMap<>();
            for (String d : resolved.topLayerArtefacts().documents()) docs.put(d, topLayerName);
            for (String s : resolved.topLayerArtefacts().settings()) settings.put(s, topLayerName);
            for (Map.Entry<String, KitResolver.LayerArtefacts> e
                    : resolved.inheritArtefacts().entrySet()) {
                for (String d : e.getValue().documents()) docs.put(d, e.getKey());
                for (String s : e.getValue().settings()) settings.put(s, e.getKey());
            }
            return new Ownership(docs, settings);
        }

        String documentLayer(String path, String fallback) {
            return documents.getOrDefault(path, fallback);
        }

        String settingLayer(String key, String fallback) {
            return settings.getOrDefault(key, fallback);
        }
    }

    /**
     * Per-artefact hashes recorded by the project's <i>other</i> installed
     * kits.
     *
     * <p>Exists to answer one question the single-kit world never had to
     * ask: an artefact whose content matches none of what this kit
     * installed may still be perfectly legitimate — a sibling kit,
     * higher in the layer order, wrote it. Only content matching neither
     * this kit nor any sibling is genuinely the user's.
     */
    private record SiblingHashes(
            Map<String, Set<String>> documents, Map<String, Set<String>> settings) {

        static SiblingHashes empty() {
            return new SiblingHashes(Map.of(), Map.of());
        }

        static SiblingHashes of(List<KitInstalledRecordDto> allRecords, String selfId) {
            Map<String, Set<String>> documents = new LinkedHashMap<>();
            Map<String, Set<String>> settings = new LinkedHashMap<>();
            for (KitInstalledRecordDto record : allRecords) {
                if (record.getId().equals(selfId) || record.getArtefacts() == null) continue;
                index(record.getArtefacts().getDocuments(), documents);
                index(record.getArtefacts().getSettings(), settings);
            }
            return new SiblingHashes(documents, settings);
        }

        private static void index(List<KitArtefactDto> artefacts, Map<String, Set<String>> into) {
            for (KitArtefactDto a : artefacts) {
                if (a.getHash() == null) continue;
                into.computeIfAbsent(a.getId(), k -> new LinkedHashSet<>()).add(a.getHash());
            }
        }

        Set<String> forDocument(String path) {
            return documents.getOrDefault(path, Set.of());
        }

        Set<String> forSetting(String key) {
            return settings.getOrDefault(key, Set.of());
        }
    }

    // ──────────────────── documents ────────────────────

    private List<KitArtefactDto> applyDocuments(
            String tenantId, String projectId, BuildTreeScan scan,
            @Nullable KitInstalledRecordDto previous, KitPolicy policy, Ownership ownership,
            String topLayerName, boolean tracked, @Nullable KitBaseTree baseTree,
            SiblingHashes siblings, @Nullable String actor,
            KitOperationResultDto.KitOperationResultDtoBuilder result) {

        Map<String, KitArtefactDto> known = indexById(
                previous == null || previous.getArtefacts() == null
                        ? List.of() : previous.getArtefacts().getDocuments());

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> skippedLocked = new ArrayList<>();
        List<String> skippedByPolicy = new ArrayList<>();
        List<String> conflicted = new ArrayList<>();
        List<KitArtefactDto> artefacts = new ArrayList<>();

        for (Map.Entry<String, String> e : scan.documents().entrySet()) {
            String path = e.getKey();
            String content = e.getValue();
            Optional<DocumentDocument> existing =
                    documentService.findByPath(tenantId, projectId, path);

            String written = content;
            if (tracked) {
                KitPolicyAction action = policy.forDocument(path);
                KitArtefactDto record = known.get(path);
                String currentContent = existing.map(documentService::readContent).orElse(null);
                KitPolicy.Decision decision = KitPolicy.decide(
                        action,
                        existing.isPresent(),
                        record == null ? null : record.getHash(),
                        currentContent == null ? null : KitHash.of(currentContent),
                        KitHash.of(content),
                        siblings.forDocument(path));

                if (decision == KitPolicy.Decision.MERGE) {
                    MergeOutcome merged = mergeDocument(
                            tenantId, projectId, path, currentContent, content,
                            baseTree, actor, result);
                    if (merged.content() == null) {
                        if (merged.conflicted()) conflicted.add(path);
                        else skippedByPolicy.add(path);
                        keepOwnership(record, artefacts);
                        continue;
                    }
                    written = merged.content();
                } else if (decision != KitPolicy.Decision.WRITE) {
                    log.debug("KitInstaller: policy {} skips document '{}/{}/{}' ({})",
                            action, tenantId, projectId, path, decision);
                    skippedByPolicy.add(path);
                    // Still owned — the kit installed it, it just is not
                    // being refreshed.
                    keepOwnership(record, artefacts);
                    continue;
                }
            }

            UpsertOutcome outcome = upsertDocument(tenantId, projectId, path, written, actor);
            switch (outcome) {
                case CREATED -> added.add(path);
                case UPDATED -> updated.add(path);
                case SKIPPED -> skippedLocked.add(path);
            }
            if (outcome == UpsertOutcome.SKIPPED) {
                keepOwnership(known.get(path), artefacts);
                continue;
            }
            // Hash of what actually landed — after a merge that is the
            // merged text, not the kit's version. Otherwise the next update
            // would read the merge as a fresh user edit.
            artefacts.add(KitArtefactDto.builder()
                    .id(path)
                    .hash(KitHash.of(written))
                    .layer(ownership.documentLayer(path, topLayerName))
                    .build());
        }

        result.documentsAdded(added)
                .documentsUpdated(updated)
                .documentsSkipped(skippedLocked)
                .documentsSkippedByPolicy(skippedByPolicy)
                .documentsConflicted(conflicted);

        refreshAffectedToolEntries(tenantId, projectId, added, updated, List.of());
        return artefacts;
    }

    /**
     * Result of a merge attempt. {@code content} is null when nothing
     * should be written — either because the merge conflicted or because
     * there was no common ancestor to merge against.
     */
    private record MergeOutcome(@Nullable String content, boolean conflicted) {}

    /**
     * Reconcile a locally modified document with the kit's new version.
     *
     * <p>A conflict neither fails the update nor overwrites the user's
     * work: the conflict-marked text is written <b>beside</b> the document
     * as {@code <path>.kit-merge} so there is something concrete to
     * resolve, and the original stays as it was.
     */
    private MergeOutcome mergeDocument(
            String tenantId, String projectId, String path,
            @Nullable String currentContent, String incoming,
            @Nullable KitBaseTree baseTree, @Nullable String actor,
            KitOperationResultDto.KitOperationResultDtoBuilder result) {

        String base = baseTree == null ? null : baseTree.documentContent(path);
        if (base == null || currentContent == null) {
            log.info("KitInstaller: no common ancestor for '{}/{}/{}' — merge falls back to keep",
                    tenantId, projectId, path);
            result.warnings(addWarning(result.build().getWarnings(),
                    "document '" + path + "' is set to merge but its previously installed"
                            + " version could not be reconstructed — kept the local version"));
            return new MergeOutcome(null, false);
        }

        KitMerge.Result merged = KitMerge.merge(base, currentContent, incoming);
        if (!merged.conflicted()) {
            log.info("KitInstaller: merged kit changes into '{}/{}/{}'", tenantId, projectId, path);
            return new MergeOutcome(merged.content(), false);
        }

        String sidecar = path + MERGE_SIDECAR_SUFFIX;
        upsertDocument(tenantId, projectId, sidecar, merged.content(), actor);
        log.info("KitInstaller: merge conflict on '{}/{}/{}' — markers written to {}",
                tenantId, projectId, path, sidecar);
        result.warnings(addWarning(result.build().getWarnings(),
                "document '" + path + "' has conflicting changes — the merged version with"
                        + " conflict markers is at '" + sidecar + "', the original is untouched"));
        return new MergeOutcome(null, true);
    }

    /**
     * Documents under {@code server-tools/<name>.yaml} carry tool
     * configs. After writing, the {@link ServerToolRegistry} needs to
     * pick the changes up — per-project caches don't notice direct
     * document writes on their own.
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
     * Write one document. New documents are created unconditionally;
     * existing documents that are {@code KIT}-locked are skipped with a
     * log entry, and the skip is reported so the operation report lists
     * the frozen paths. Other lock levels (AI, USER) do not block
     * kit writes — they are about user- and LLM-driven edits.
     *
     * @return {@code CREATED} on first write, {@code UPDATED} on a
     *         successful overwrite, {@code SKIPPED} when the document is
     *         frozen against kit writes (KIT lock).
     */
    private UpsertOutcome upsertDocument(
            String tenantId, String projectId, String path, String content,
            @Nullable String actor) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isEmpty()) {
            documentService.createText(tenantId, projectId, path, null, null, content, actor,
                    WriteActor.SYSTEM);
            return UpsertOutcome.CREATED;
        }
        DocumentDocument doc = existing.get();
        if (documentService.readContent(doc) != null) {
            try {
                documentService.update(doc.getId(),
                        /*title*/ null, /*tags*/ null, /*inlineText*/ content,
                        /*newPath*/ null, /*autoSummary*/ null,
                        /*summaryDirty*/ null, /*ragEnabled*/ null,
                        /*mimeType*/ null, DocumentService.KIT_IDENTITY, WriteActor.SYSTEM);
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
            documentService.delete(doc.getId(), DocumentService.KIT_IDENTITY, WriteActor.SYSTEM);
        } catch (DocumentService.DocumentLockedException e) {
            log.info("KitInstaller: skipped KIT-locked document '{}/{}/{}' lockedFor={}",
                    tenantId, projectId, path, e.getLockedFor());
            return UpsertOutcome.SKIPPED;
        }
        documentService.createText(tenantId, projectId, path, null, null, content, actor,
                WriteActor.SYSTEM);
        return UpsertOutcome.UPDATED;
    }

    /** Result of {@link #upsertDocument} — drives the per-path counters in the op report. */
    private enum UpsertOutcome { CREATED, UPDATED, SKIPPED }

    // ──────────────────── settings ────────────────────

    private List<KitArtefactDto> applySettings(
            String tenantId, String projectId, BuildTreeScan scan,
            @Nullable KitInstalledRecordDto previous, KitPolicy policy, Ownership ownership,
            String topLayerName, KitImportMode mode, boolean keepPasswords,
            @Nullable String vaultPassword, boolean kitDeclaresEncrypted,
            SiblingHashes siblings, SettingWriteOrigin origin,
            KitOperationResultDto.KitOperationResultDtoBuilder result) {

        boolean tracked = mode != KitImportMode.APPLY;
        Map<String, KitArtefactDto> known = indexById(
                previous == null || previous.getArtefacts() == null
                        ? List.of() : previous.getArtefacts().getSettings());

        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> skippedPw = new ArrayList<>();
        List<String> skippedByPolicy = new ArrayList<>();
        List<KitArtefactDto> artefacts = new ArrayList<>();
        boolean haveWarnedAboutMissingVault = false;

        for (Map.Entry<String, KitYamlMapper.ParsedSetting> entry : scan.settings().entrySet()) {
            String key = entry.getKey();
            KitYamlMapper.ParsedSetting parsed = entry.getValue();
            // W3 — an agent picks the kit's git URL, so a crafted kit must not be
            // able to write reserved operator keys. Applies to every type, not
            // just the encrypted ones: overwriting ai.provider.*.apiKey with a
            // plain STRING would be just as destructive. Skip + report rather
            // than abort, matching how this loop handles every other refusal.
            if (origin == SettingWriteOrigin.AGENT && agentKeyPolicy.isDenied(key)) {
                log.warn("Kit install: refusing agent-originated write to reserved "
                        + "setting key '{}' (tenant='{}' project='{}')", key, tenantId, projectId);
                result.warnings(addWarning(result.build().getWarnings(),
                        "setting '" + key + "' is reserved for operator configuration "
                                + "and was not written"));
                keepOwnership(known.get(key), artefacts);
                continue;
            }
            boolean existed = settingService.exists(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);

            if (tracked) {
                KitPolicyAction action = policy.forSetting(key);
                KitArtefactDto record = known.get(key);
                // getStringValue refuses encrypted types and returns null —
                // exactly what we want: an encrypted setting has no comparable
                // hash, so `keep` leaves an existing credential alone.
                String currentValue = existed
                        ? settingService.getStringValue(
                                tenantId, SettingService.SCOPE_PROJECT, projectId, key)
                        : null;
                KitPolicy.Decision decision = KitPolicy.decide(
                        action, existed,
                        record == null ? null : record.getHash(),
                        currentValue == null ? null : KitHash.of(currentValue),
                        parsed.type().encrypted() || parsed.value() == null
                                ? null : KitHash.of(parsed.value()),
                        siblings.forSetting(key));
                // MERGE lands here too and behaves as KEEP: a setting value
                // is not a text file, and merging one line by line would be
                // theatre. Documented on KitPolicyAction.MERGE.
                if (decision != KitPolicy.Decision.WRITE) {
                    log.debug("KitInstaller: policy {} skips setting '{}/{}/{}' ({})",
                            action, tenantId, projectId, key, decision);
                    skippedByPolicy.add(key);
                    keepOwnership(record, artefacts);
                    continue;
                }
            }

            if (parsed.type().encrypted()) {
                if (mode == KitImportMode.APPLY && keepPasswords) {
                    log.debug("Skipping encrypted setting '{}' due to --keep-passwords", key);
                    continue;
                }
                if (vaultPassword == null || vaultPassword.isBlank()) {
                    if (!haveWarnedAboutMissingVault && kitDeclaresEncrypted) {
                        result.warnings(addWarning(result.build().getWarnings(),
                                "vault password not provided — encrypted settings skipped"));
                        haveWarnedAboutMissingVault = true;
                    }
                    skippedPw.add(key);
                    keepOwnership(known.get(key), artefacts);
                    continue;
                }
                // The kit's declared type is preserved — never promoted to
                // PASSWORD (it would stop resolving through the tool documents
                // the same kit installs) and never demoted to HIDDEN (the value
                // came from the repo, not from anyone's context).
                boolean ok = settingService.encryptFromImport(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key,
                        vaultPassword, parsed.value(), parsed.type());
                if (!ok) {
                    skippedPw.add(key);
                    keepOwnership(known.get(key), artefacts);
                    continue;
                }
            } else {
                settingService.set(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key,
                        parsed.value(), parsed.type(), parsed.description());
            }
            if (existed) updated.add(key);
            else added.add(key);

            artefacts.add(KitArtefactDto.builder()
                    .id(key)
                    // Encrypted values are re-encrypted with a fresh IV on every
                    // write, so a ciphertext hash would compare noise. Null means
                    // "unverifiable" and `keep` treats it as user-owned.
                    .hash(parsed.type().encrypted() || parsed.value() == null
                            ? null : KitHash.of(parsed.value()))
                    .layer(ownership.settingLayer(key, topLayerName))
                    .build());
        }

        result.settingsAdded(added)
                .settingsUpdated(updated)
                .settingsSkippedByPolicy(skippedByPolicy)
                .skippedPasswords(skippedPw);
        return artefacts;
    }

    private static List<String> addWarning(List<String> existing, String warning) {
        List<String> out = new ArrayList<>(existing);
        out.add(warning);
        return out;
    }

    /**
     * Carry a previously recorded artefact over when this run did not
     * write it.
     *
     * <p>Skipping is not disowning. An update run without the vault
     * passphrase, say, leaves the encrypted settings alone — dropping them
     * from the record as well would lose their layer and ownership, and a
     * later prune would no longer know the kit ever had them.
     */
    private static void keepOwnership(
            @Nullable KitArtefactDto previous, List<KitArtefactDto> artefacts) {
        if (previous != null) artefacts.add(previous);
    }

    private static Map<String, KitArtefactDto> indexById(List<KitArtefactDto> artefacts) {
        Map<String, KitArtefactDto> out = new LinkedHashMap<>();
        for (KitArtefactDto a : artefacts) out.put(a.getId(), a);
        return out;
    }

    // ──────────────────── prune ────────────────────

    /**
     * Handle artefacts this kit owned before but no longer ships.
     *
     * <p>Deleting is gated twice. Once by {@code prune} — the default is
     * non-destructive, the artefact merely drops out of the record. And
     * once by other kits: with several kits installed, the same path may
     * belong to another record, and removing it would silently strip a
     * kit the user never touched. In that case the artefact only leaves
     * this record.
     */
    private void pruneLostArtefacts(
            String tenantId, String projectId, String recordId,
            @Nullable KitInstalledRecordDto previous, BuildTreeScan scan, boolean prune,
            KitOperationResultDto.KitOperationResultDtoBuilder result) {

        List<String> removedDocuments = new ArrayList<>();
        List<String> removedSettings = new ArrayList<>();
        if (previous == null || previous.getArtefacts() == null) {
            result.documentsRemoved(removedDocuments).settingsRemoved(removedSettings);
            return;
        }

        Set<String> otherDocuments = new HashSet<>();
        Set<String> otherSettings = new HashSet<>();
        for (KitInstalledRecordDto other : recordStore.list(tenantId, projectId)) {
            if (other.getId().equals(recordId)) continue;
            if (other.getArtefacts() == null) continue;
            for (KitArtefactDto a : other.getArtefacts().getDocuments()) otherDocuments.add(a.getId());
            for (KitArtefactDto a : other.getArtefacts().getSettings()) otherSettings.add(a.getId());
        }

        for (KitArtefactDto a : previous.getArtefacts().getDocuments()) {
            if (scan.documents().containsKey(a.getId())) continue;
            if (!prune) continue;
            if (otherDocuments.contains(a.getId())) {
                log.info("KitInstaller: not pruning document '{}/{}/{}' — still owned by another kit",
                        tenantId, projectId, a.getId());
                continue;
            }
            Optional<DocumentDocument> doc =
                    documentService.findByPath(tenantId, projectId, a.getId());
            if (doc.isEmpty()) continue;
            try {
                documentService.delete(doc.get().getId(), DocumentService.KIT_IDENTITY,
                        WriteActor.SYSTEM);
                removedDocuments.add(a.getId());
                refreshOneIfTool(tenantId, projectId, a.getId());
            } catch (DocumentService.DocumentLockedException ex) {
                log.info("KitInstaller: skipped prune of KIT-locked document '{}/{}/{}' lockedFor={}",
                        tenantId, projectId, a.getId(), ex.getLockedFor());
            }
        }

        for (KitArtefactDto a : previous.getArtefacts().getSettings()) {
            if (scan.settings().containsKey(a.getId())) continue;
            if (!prune) continue;
            if (otherSettings.contains(a.getId())) {
                log.info("KitInstaller: not pruning setting '{}/{}/{}' — still owned by another kit",
                        tenantId, projectId, a.getId());
                continue;
            }
            settingService.delete(tenantId, SettingService.SCOPE_PROJECT, projectId, a.getId());
            removedSettings.add(a.getId());
        }

        result.documentsRemoved(removedDocuments).settingsRemoved(removedSettings);
    }

    /**
     * Remove everything one kit owns and forget the record. Artefacts
     * another installed kit also claims are left in place — same rule as
     * prune, for the same reason.
     */
    public KitOperationResultDto uninstall(
            String tenantId, String projectId, KitInstalledRecordDto record, boolean prune) {

        BuildTreeScan empty = new BuildTreeScan(Map.of(), Map.of());
        KitOperationResultDto.KitOperationResultDtoBuilder result =
                KitOperationResultDto.builder()
                        .kitName(record.getKit().getName())
                        .kitId(record.getId())
                        .mode("UNINSTALL");
        pruneLostArtefacts(tenantId, projectId, record.getId(), record, empty, prune, result);
        recordStore.delete(tenantId, projectId, record.getId());
        // The user's config document deliberately survives: they wrote it,
        // and it becomes effective again if the kit is reinstalled.
        return result.build();
    }

    // ──────────────────── record ────────────────────

    private KitInstalledRecordDto buildRecord(
            String recordId, KitDescriptorDto top, KitInheritDto source,
            KitResolver.ResolvedKit resolved,
            List<KitArtefactDto> documents, List<KitArtefactDto> settings,
            BuildTreeScan scan, @Nullable String actor) {
        return KitInstalledRecordDto.builder()
                .id(recordId)
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
                .descriptor(top)
                .artefacts(KitArtefactsDto.builder()
                        .documents(documents)
                        .settings(settings)
                        .build())
                .hasEncryptedSecrets(hasAnyEncryptedSetting(scan))
                .signatureStatus(resolved.topLayerSignature())
                .sourceId(resolved.topLayerSourceId())
                .build();
    }

    // ──────────────────── authoring manifest ────────────────────

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
                .hasEncryptedSecrets(hasAnyEncryptedSetting(scan))
                .build();
    }

    /**
     * Build the authoring manifest from an install record instead of a
     * fresh clone — the record already carries origin, descriptor and
     * per-layer ownership, so promoting an installed kit to a kit under
     * development costs nothing but this mapping.
     */
    public KitManifestDto manifestFromRecord(KitInstalledRecordDto record) {
        String topLayer = record.getKit().getName();
        List<String> topDocuments = new ArrayList<>();
        List<String> topSettings = new ArrayList<>();
        Map<String, InheritBucket> perInherit = new LinkedHashMap<>();

        for (KitArtefactDto a : record.getArtefacts().getDocuments()) {
            if (topLayer.equals(a.getLayer())) topDocuments.add(a.getId());
            else inheritOf(perInherit, a.getLayer()).documents.add(a.getId());
        }
        for (KitArtefactDto a : record.getArtefacts().getSettings()) {
            if (topLayer.equals(a.getLayer())) topSettings.add(a.getId());
            else inheritOf(perInherit, a.getLayer()).settings.add(a.getId());
        }

        List<InheritArtefactsDto> inheritArtefacts = new ArrayList<>();
        for (InheritBucket bucket : perInherit.values()) {
            inheritArtefacts.add(bucket.build());
        }

        KitDescriptorDto descriptor = record.getDescriptor();
        return KitManifestDto.builder()
                .kit(record.getKit())
                .origin(record.getOrigin())
                .documents(topDocuments)
                .settings(topSettings)
                .tools(new ArrayList<>())
                .inherits(descriptor == null || descriptor.getInherits() == null
                        ? new ArrayList<>() : new ArrayList<>(descriptor.getInherits()))
                .resolvedInherits(new ArrayList<>(perInherit.keySet()))
                .inheritArtefacts(inheritArtefacts)
                .hasEncryptedSecrets(record.isHasEncryptedSecrets())
                .build();
    }

    /** Accumulator for {@link #manifestFromRecord} — one bucket per contributing inherit layer. */
    private static final class InheritBucket {
        private final String name;
        private final List<String> documents = new ArrayList<>();
        private final List<String> settings = new ArrayList<>();

        InheritBucket(String name) {
            this.name = name;
        }

        InheritArtefactsDto build() {
            return InheritArtefactsDto.builder()
                    .name(name)
                    .documents(documents)
                    .settings(settings)
                    .tools(new ArrayList<>())
                    .build();
        }
    }

    private static InheritBucket inheritOf(
            Map<String, InheritBucket> buckets, @Nullable String layer) {
        String name = layer == null || layer.isBlank() ? "unknown" : layer;
        return buckets.computeIfAbsent(name, InheritBucket::new);
    }

    private static boolean hasAnyEncryptedSetting(BuildTreeScan scan) {
        for (KitYamlMapper.ParsedSetting s : scan.settings().values()) {
            if (s.type().encrypted()) return true;
        }
        return false;
    }

    /** Set-based union helper used by tests. */
    static Set<String> unionKeys(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }
}
