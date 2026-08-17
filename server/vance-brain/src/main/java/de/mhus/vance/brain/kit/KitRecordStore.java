package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.permission.WriteActor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Document access for the kit subsystem: install records, the optional
 * user config beside each one, and the authoring manifest.
 *
 * <p>Three paths, three owners:
 *
 * <ul>
 *   <li>{@code _vance/kits/installed/<id>.yaml} — one per installed kit,
 *       written exclusively here, rewritten in full on every update.</li>
 *   <li>{@code _vance/kits/config/<id>.yaml} — optional, hand-written by
 *       the user. This class <b>reads</b> it; {@link #saveConfig} exists
 *       for the UI's policy editor, never for the install path.</li>
 *   <li>{@code _vance/kits/manifest.yaml} — at most one, says "this
 *       project is a kit source". Only written on explicit request.</li>
 * </ul>
 *
 * <p>Spec: {@code planning/kit-installed-multi.md} §2, §D10.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitRecordStore {

    /** Everything the kit subsystem owns. Kits may not write in here — see {@link #isReservedPath}. */
    public static final String KITS_PREFIX = "_vance/kits/";

    public static final String INSTALLED_PREFIX = KITS_PREFIX + "installed/";

    public static final String CONFIG_PREFIX = KITS_PREFIX + "config/";

    public static final String MANIFEST_PATH = KITS_PREFIX + "manifest.yaml";

    private static final String YAML_SUFFIX = ".yaml";

    private final DocumentService documentService;

    // ──────────────────── install records ────────────────────

    /**
     * All installed kits of a project, in layer order: lowest priority
     * first, so a later entry overwrites an earlier one on artefact
     * collision.
     *
     * <p>Order is {@code installedAt} unless the kit's config document
     * overrides it with {@code sortIndex} — the default matches what a
     * user expects without configuring anything ("the kit I installed
     * last wins"), and the override exists for when it doesn't.
     */
    public List<KitInstalledRecordDto> listInLayerOrder(String tenantId, String projectId) {
        List<KitInstalledRecordDto> records = list(tenantId, projectId);
        records.sort(Comparator
                .comparingInt((KitInstalledRecordDto r) -> layerRank(tenantId, projectId, r))
                .thenComparing(KitInstalledRecordDto::getId));
        return records;
    }

    private int layerRank(String tenantId, String projectId, KitInstalledRecordDto record) {
        Integer explicit = loadConfig(tenantId, projectId, record.getId()).getSortIndex();
        if (explicit != null) return explicit;
        // Without an explicit index, fall back to install time. Seconds are
        // plenty — two kits installed in the same second are ordered by id,
        // which is at least stable.
        return record.getOrigin() != null && record.getOrigin().getInstalledAt() != null
                ? (int) record.getOrigin().getInstalledAt().getEpochSecond()
                : 0;
    }

    /** Unordered list of every parseable install record in the project. */
    public List<KitInstalledRecordDto> list(String tenantId, String projectId) {
        List<KitInstalledRecordDto> out = new ArrayList<>();
        for (DocumentDocument doc
                : documentService.listUnderFolder(tenantId, projectId, INSTALLED_PREFIX)) {
            if (!doc.getPath().endsWith(YAML_SUFFIX)) continue;
            String content = readText(doc, doc.getPath());
            if (content == null) continue;
            try {
                out.add(KitYamlMapper.parseInstalledRecord(content));
            } catch (KitException e) {
                // One broken record must not hide the other installed kits —
                // the user needs the list to fix it in the first place.
                log.warn("KitRecordStore: skipping malformed install record '{}/{}/{}': {}",
                        tenantId, projectId, doc.getPath(), e.getMessage());
            }
        }
        return out;
    }

    public @Nullable KitInstalledRecordDto find(String tenantId, String projectId, String id) {
        for (KitInstalledRecordDto record : list(tenantId, projectId)) {
            if (record.getId().equals(id)) return record;
        }
        return null;
    }

    /**
     * Find the record for a set of source coordinates.
     *
     * <p>The id contains the kit's <i>name</i> for readability, but the
     * identity is {@code (url, path)} alone. Looking up by id would
     * therefore lose the record the moment a kit renames itself in a new
     * version: the update would fork into a second record, prune would
     * find nothing to compare against, and every already-installed file
     * would look like a stranger's under the default keep policy.
     */
    public @Nullable KitInstalledRecordDto findByOrigin(
            String tenantId, String projectId, String url, @Nullable String path) {
        String wanted = KitRecordId.originKey(url, path);
        for (KitInstalledRecordDto record : list(tenantId, projectId)) {
            KitOriginDto origin = record.getOrigin();
            if (origin == null) continue;
            if (wanted.equals(KitRecordId.originKey(origin.getUrl(), origin.getPath()))) {
                return record;
            }
        }
        return null;
    }

    public void save(String tenantId, String projectId, KitInstalledRecordDto record,
            @Nullable String actor) {
        writeDocument(tenantId, projectId, recordPath(record.getId()),
                KitYamlMapper.writeInstalledRecord(record),
                "Kit: " + record.getKit().getName(), actor);
    }

    public void delete(String tenantId, String projectId, String id) {
        deleteDocument(tenantId, projectId, recordPath(id));
    }

    public static String recordPath(String id) {
        return INSTALLED_PREFIX + id + YAML_SUFFIX;
    }

    // ──────────────────── user config ────────────────────

    /**
     * The user's config for one kit, or the defaults when no config
     * document exists — which is the normal case. A malformed config is
     * <b>not</b> silently defaulted: it means the user wrote something
     * they believe is in effect, and quietly ignoring it would apply the
     * opposite of their intent to their own files.
     */
    public KitConfigDto loadConfig(String tenantId, String projectId, String id) {
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectId, configPath(id));
        if (doc.isEmpty()) return KitConfigDto.builder().build();
        String content = readText(doc.get(), configPath(id));
        if (content == null || content.isBlank()) return KitConfigDto.builder().build();
        return KitYamlMapper.parseConfig(content);
    }

    /**
     * Write a config document. Used by the UI's policy editor — the
     * install path never calls this, which is the whole point of keeping
     * config out of the record.
     */
    public void saveConfig(String tenantId, String projectId, String id, KitConfigDto config,
            @Nullable String actor) {
        writeDocument(tenantId, projectId, configPath(id),
                KitYamlMapper.writeConfig(config), "Kit config: " + id, actor);
    }

    public static String configPath(String id) {
        return CONFIG_PREFIX + id + YAML_SUFFIX;
    }

    // ──────────────────── authoring manifest ────────────────────

    public @Nullable KitManifestDto loadManifest(String tenantId, String projectId) {
        Optional<DocumentDocument> doc =
                documentService.findByPath(tenantId, projectId, MANIFEST_PATH);
        if (doc.isEmpty()) return null;
        String content = readText(doc.get(), MANIFEST_PATH);
        if (content == null) return null;
        try {
            return KitYamlMapper.parseManifest(content);
        } catch (KitException e) {
            log.warn("KitRecordStore: manifest at {} is malformed: {} — treating as absent",
                    MANIFEST_PATH, e.getMessage());
            return null;
        }
    }

    public void saveManifest(String tenantId, String projectId, KitManifestDto manifest,
            @Nullable String actor) {
        writeDocument(tenantId, projectId, MANIFEST_PATH,
                KitYamlMapper.writeManifest(manifest), "Kit Manifest", actor);
    }

    public void removeManifest(String tenantId, String projectId) {
        deleteDocument(tenantId, projectId, MANIFEST_PATH);
    }

    // ──────────────────── guard ────────────────────

    /**
     * True for paths a kit must never ship. A kit that could write into
     * {@code _vance/kits/} would forge its own install record — set a
     * competing kit's policy to {@code ignore}, rewrite ownership,
     * embellish its own descriptor. Harmless for a kit a colleague
     * shares, not harmless for one that was bought.
     */
    public static boolean isReservedPath(String documentPath) {
        String normalized = documentPath.startsWith("/")
                ? documentPath.substring(1) : documentPath;
        return normalized.startsWith(KITS_PREFIX);
    }

    // ──────────────────── document plumbing ────────────────────

    private @Nullable String readText(DocumentDocument doc, String path) {
        String content = documentService.readContent(doc);
        if (content != null) return content;
        try (var in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to read " + path, e);
        }
    }

    private void writeDocument(String tenantId, String projectId, String path, String yaml,
            String title, @Nullable String actor) {
        Optional<DocumentDocument> existing =
                documentService.findByPath(tenantId, projectId, path);
        if (existing.isEmpty()) {
            documentService.createText(tenantId, projectId, path, title,
                    List.of("vance", "kit"), yaml, actor, WriteActor.SYSTEM);
            return;
        }
        DocumentDocument doc = existing.get();
        if (documentService.readContent(doc) != null) {
            try {
                documentService.update(doc.getId(),
                        /*title*/ null, /*tags*/ null, /*inlineText*/ yaml,
                        /*newPath*/ null, /*autoSummary*/ null,
                        /*summaryDirty*/ null, /*ragEnabled*/ null,
                        /*mimeType*/ null, DocumentService.KIT_IDENTITY, WriteActor.SYSTEM);
                return;
            } catch (IllegalArgumentException e) {
                log.debug("inline update rejected for {} — recreating: {}", path, e.getMessage());
            }
        }
        documentService.delete(doc.getId(), DocumentService.KIT_IDENTITY, WriteActor.SYSTEM);
        documentService.createText(tenantId, projectId, path, title,
                List.of("vance", "kit"), yaml, actor, WriteActor.SYSTEM);
    }

    private void deleteDocument(String tenantId, String projectId, String path) {
        documentService.findByPath(tenantId, projectId, path)
                .ifPresent(doc -> documentService.delete(doc.getId(),
                        DocumentService.KIT_IDENTITY, WriteActor.SYSTEM));
    }
}
