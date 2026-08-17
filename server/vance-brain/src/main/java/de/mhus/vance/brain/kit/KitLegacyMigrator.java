package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.InheritArtefactsDto;
import de.mhus.vance.api.kit.KitArtefactDto;
import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.permission.WriteActor;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Converts a project still carrying the pre-multi-kit
 * {@code _vance/kit-manifest.yaml} into an install record.
 *
 * <p>Deliberately a separate, explicitly invoked step rather than
 * automatic on read: a silent conversion during an unrelated operation
 * is the kind of thing that is impossible to reason about afterwards.
 *
 * <p>Only projects whose kit was <b>tracked</b> can be migrated — the old
 * manifest carries origin and artefact lists, which is everything a
 * record needs. Kits that were splatted in via {@code apply} left no
 * trace by design and nothing can reconstruct them.
 *
 * <p>Spec: {@code planning/kit-installed-multi.md} §D9.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitLegacyMigrator {

    /** Where the single active kit was recorded before the rework. */
    public static final String LEGACY_MANIFEST_PATH = "_vance/kit-manifest.yaml";

    private final DocumentService documentService;
    private final SettingService settingService;
    private final KitRecordStore recordStore;

    /**
     * What a migration did, or why it did nothing.
     *
     * @param migrated true when a record was written
     * @param kitId id of the record, null when nothing was migrated
     * @param documents number of document artefacts carried over
     * @param settings number of setting artefacts carried over
     * @param message human-readable outcome, always set
     */
    public record Result(
            boolean migrated,
            @Nullable String kitId,
            int documents,
            int settings,
            String message) {}

    /** True when the project still has an old manifest to convert. */
    public boolean hasLegacyManifest(String tenantId, String projectId) {
        return documentService.findByPath(tenantId, projectId, LEGACY_MANIFEST_PATH).isPresent();
    }

    /**
     * Convert the legacy manifest into an install record.
     *
     * @param keepAsKitSource also write the new authoring manifest, i.e.
     *        treat this project as the source of the kit. Off by default:
     *        under the old model every tracked install wrote a manifest,
     *        so its presence says nothing about whether anyone actually
     *        authors this kit here.
     */
    public Result migrate(String tenantId, String projectId, boolean keepAsKitSource,
            @Nullable String actor) {
        Optional<DocumentDocument> legacy =
                documentService.findByPath(tenantId, projectId, LEGACY_MANIFEST_PATH);
        if (legacy.isEmpty()) {
            return new Result(false, null, 0, 0,
                    "project " + projectId + " has no " + LEGACY_MANIFEST_PATH + " to migrate");
        }

        KitManifestDto manifest = parse(legacy.get());
        if (manifest.getOrigin() == null || manifest.getOrigin().getUrl() == null) {
            return new Result(false, null, 0, 0,
                    LEGACY_MANIFEST_PATH + " has no usable origin — nothing to migrate from");
        }

        String kitId = KitRecordId.of(
                manifest.getKit().getName(),
                manifest.getOrigin().getUrl(),
                manifest.getOrigin().getPath());
        if (recordStore.find(tenantId, projectId, kitId) != null) {
            return new Result(false, kitId, 0, 0,
                    "kit '" + kitId + "' is already installed as a record — "
                            + "delete " + LEGACY_MANIFEST_PATH + " by hand if it is stale");
        }

        String topLayer = manifest.getKit().getName();
        List<KitArtefactDto> documents = new ArrayList<>();
        List<KitArtefactDto> settings = new ArrayList<>();
        collectDocuments(tenantId, projectId, manifest.getDocuments(), topLayer, documents);
        collectSettings(tenantId, projectId, manifest.getSettings(), topLayer, settings);
        for (InheritArtefactsDto inherit : manifest.getInheritArtefacts()) {
            collectDocuments(tenantId, projectId, inherit.getDocuments(),
                    inherit.getName(), documents);
            collectSettings(tenantId, projectId, inherit.getSettings(),
                    inherit.getName(), settings);
        }

        KitInstalledRecordDto record = KitInstalledRecordDto.builder()
                .id(kitId)
                .kit(manifest.getKit())
                .origin(manifest.getOrigin())
                // The old manifest kept only the inherits list, not the whole
                // descriptor. Reconstruct what it did carry; the rest arrives
                // with the next update.
                .descriptor(KitDescriptorDto.builder()
                        .name(manifest.getKit().getName())
                        .description(manifest.getKit().getDescription())
                        .version(manifest.getKit().getVersion())
                        .inherits(new ArrayList<>(manifest.getInherits()))
                        .hasEncryptedSecrets(manifest.isHasEncryptedSecrets())
                        .build())
                .artefacts(KitArtefactsDto.builder()
                        .documents(documents)
                        .settings(settings)
                        .build())
                .hasEncryptedSecrets(manifest.isHasEncryptedSecrets())
                .build();

        recordStore.save(tenantId, projectId, record, actor);
        if (keepAsKitSource) {
            recordStore.saveManifest(tenantId, projectId, manifest, actor);
        }
        documentService.delete(legacy.get().getId(), DocumentService.KIT_IDENTITY,
                WriteActor.SYSTEM);

        log.info("KitLegacyMigrator: migrated '{}' in {}/{} — {} documents, {} settings,"
                        + " kitSource={}",
                kitId, tenantId, projectId, documents.size(), settings.size(), keepAsKitSource);
        return new Result(true, kitId, documents.size(), settings.size(),
                "migrated kit '" + manifest.getKit().getName() + "' to record '" + kitId + "'");
    }

    /**
     * Hash what is in the project right now, not what was installed once.
     *
     * <p>The old manifest carries no hashes, and the alternative — leaving
     * them null — would mark every artefact as "possibly user-owned" and
     * so freeze the kit under the default {@code keep} policy. Taking the
     * present state as the installed state is the assumption the manifest
     * itself makes by listing these artefacts as the kit's.
     */
    private void collectDocuments(String tenantId, String projectId, List<String> paths,
            String layer, List<KitArtefactDto> out) {
        for (String path : paths) {
            Optional<DocumentDocument> doc =
                    documentService.findByPath(tenantId, projectId, path);
            if (doc.isEmpty()) continue;
            String content = documentService.readContent(doc.get());
            out.add(KitArtefactDto.builder()
                    .id(path)
                    .hash(content == null ? null : KitHash.of(content))
                    .layer(layer)
                    .build());
        }
    }

    private void collectSettings(String tenantId, String projectId, List<String> keys,
            String layer, List<KitArtefactDto> out) {
        for (String key : keys) {
            if (!settingService.exists(tenantId, SettingService.SCOPE_PROJECT, projectId, key)) {
                continue;
            }
            // Encrypted settings return null here by design, which is
            // exactly the hash we want to record for them.
            String value = settingService.getStringValue(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            out.add(KitArtefactDto.builder()
                    .id(key)
                    .hash(value == null ? null : KitHash.of(value))
                    .layer(layer)
                    .build());
        }
    }

    private KitManifestDto parse(DocumentDocument doc) {
        String content = documentService.readContent(doc);
        if (content == null) {
            try (var in = documentService.loadContent(doc)) {
                content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new KitException("failed to read " + LEGACY_MANIFEST_PATH, e);
            }
        }
        return KitYamlMapper.parseManifest(content);
    }
}
