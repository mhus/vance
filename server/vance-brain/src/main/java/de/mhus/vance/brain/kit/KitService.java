package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Public entry point for the kit subsystem. Wraps the
 * loader/resolver/installer/exporter chain in clean
 * {@code install/update/uninstall/apply/export/status} verbs.
 *
 * <p>A project holds any number of installed kits, one install record
 * each. Installing is the everyday act; marking a project as a kit
 * <i>source</i> (the authoring manifest, which {@code export} works
 * from) is a separate, opt-in decision — see {@link #promoteToAuthoring}.
 *
 * <p>Every operation is project-scoped — the caller passes the target
 * project explicitly. Whether that's a regular project, the
 * tenant-wide {@code _vance} project, or a per-user
 * {@code _user_<id>} project does not matter to the service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitService {

    private final KitResolver resolver;
    private final KitInstaller installer;
    private final KitExporter exporter;
    private final KitWorkspace workspace;
    private final KitRecordStore recordStore;
    private final KitLegacyMigrator legacyMigrator;
    private final ProjectService projectService;
    private final TemplateApplier templateApplier;
    private final KitStoreCredentials storeCredentials;

    /**
     * Install / update / apply a kit. The {@code mode} on the request
     * selects the variant: {@code INSTALL} and {@code UPDATE} write an
     * install record, {@code APPLY} deliberately writes nothing but the
     * artefacts themselves.
     */
    public KitOperationResultDto importKit(
            String tenantId, KitImportRequestDto request, @Nullable String actor,
            SettingWriteOrigin origin) {
        validateImport(request);
        requireProject(tenantId, request.getProjectId());

        // One lookup for the whole operation: which store account this
        // installation is signed in to, and the credential to fetch with.
        // Both the resolve and the install below need it, and asking twice
        // could answer differently if a setting changed in between.
        // Params ride along on the access object because that is what reaches
        // the loader; they are not a credential and not identity, but a source
        // that assembles per request cannot be served without them.
        KitAccess access = storeCredentials.resolve(
                tenantId, request.getProjectId(), actor,
                request.getSource() == null ? null : request.getSource().getUrl(),
                request.getToken())
                .withParams(request.getParams())
                .withInstallId(previousInstallId(tenantId, request))
                .withProvisioningStamp(request.getProvisioningStamp());

        KitResolver.ResolvedKit resolved = null;
        try {
            resolved = resolver.resolve(access, request.getSource());
            KitDescriptorDto top = resolved.topLayer();
            validateResolvedTopLayer(top, request.isWriteManifest());

            // Identity is the source coordinates, so "install what is already
            // installed" is not an error we can silently absorb — the user
            // would expect an update and get a surprise re-write instead.
            // By origin, not by derived id: the installer resolves the previous
            // record that way because identity is (url, path). Looking it up by
            // id here would miss a kit that renamed itself — INSTALL would then
            // slip through and silently perform an update, which is the exact
            // surprise this guard exists to prevent.
            KitInstalledRecordDto existing = recordStore.findByOrigin(
                    tenantId, request.getProjectId(),
                    request.getSource().getUrl(), request.getSource().getPath());
            if (request.getMode() == KitImportMode.INSTALL && existing != null) {
                throw new KitException("kit '" + top.getName() + "' is already installed in project "
                        + request.getProjectId() + " (record '" + existing.getId()
                        + "') — use update");
            }

            return installer.apply(
                    access,
                    request.getProjectId(),
                    request.getSource(),
                    resolved,
                    request.getMode(),
                    request.isPrune(),
                    request.isKeepPasswords(),
                    request.getVaultPassword(),
                    request.isWriteManifest(),
                    origin,
                    actor);
        } finally {
            if (resolved != null) resolved.cleanup(workspace);
        }
    }

    /**
     * Enforce the visibility flags of the resolved top-layer descriptor
     * before any Mongo write happens. Spec: kits.md §3.2.
     *
     * <p>{@code artifact: true} gates the <b>authoring manifest</b>, not
     * the install record: the flag's rationale is that export would work
     * from an incomplete base, and export is the manifest's job.
     * Tracking a tuning bundle so it can be updated is useful and was
     * only ever forbidden because record and manifest were the same file.
     */
    private static void validateResolvedTopLayer(KitDescriptorDto top, boolean writeManifest) {
        if (!top.isInstallable()) {
            throw new KitException("kit '" + top.getName()
                    + "' is marked installable=false — usable only as an inherits: entry,"
                    + " not for direct import");
        }
        if (top.isArtifact() && writeManifest) {
            throw new KitException("kit '" + top.getName()
                    + "' is marked as artifact — it is a tuning bundle, not a complete kit,"
                    + " and cannot serve as this project's kit source. Install it without"
                    + " the authoring manifest.");
        }
    }

    /**
     * Id of the record a fetch would refresh, or null when this kit has
     * never been installed here.
     *
     * <p>Resolved centrally rather than per caller so every path — install,
     * update, provisioning — tells a source the same thing. Only a
     * <em>previous</em> installation can be named: a new record's id is
     * derived from the kit name in the descriptor, which is what is about to
     * be downloaded.
     *
     * <p>The installer looks the same record up again a moment later. Two
     * scans of a short list beats threading a record through five
     * signatures for a value only the fetch needs.
     */
    private @Nullable String previousInstallId(String tenantId, KitImportRequestDto request) {
        KitInheritDto source = request.getSource();
        if (source == null || request.getProjectId() == null) return null;
        KitInstalledRecordDto previous = recordStore.findByOrigin(
                tenantId, request.getProjectId(), source.getUrl(), source.getPath());
        return previous == null ? null : previous.getId();
    }

    /** Convenience wrapper: forces {@link KitImportMode#INSTALL}. */
    public KitOperationResultDto install(
            String tenantId, KitImportRequestDto request, @Nullable String actor,
            SettingWriteOrigin origin) {
        request.setMode(KitImportMode.INSTALL);
        return importKit(tenantId, request, actor, origin);
    }

    /** Convenience wrapper: forces {@link KitImportMode#UPDATE}. */
    public KitOperationResultDto update(
            String tenantId, KitImportRequestDto request, @Nullable String actor,
            SettingWriteOrigin origin) {
        request.setMode(KitImportMode.UPDATE);
        return importKit(tenantId, request, actor, origin);
    }

    /** Convenience wrapper: forces {@link KitImportMode#APPLY}. */
    public KitOperationResultDto apply(
            String tenantId, KitImportRequestDto request, @Nullable String actor,
            SettingWriteOrigin origin) {
        request.setMode(KitImportMode.APPLY);
        return importKit(tenantId, request, actor, origin);
    }

    // ──────────────────── update by record ────────────────────

    /**
     * Re-run one installed kit against its source. The record supplies
     * url, path and branch; the pinned {@code commit} is deliberately
     * <b>not</b> reused — it records what is installed, not what to
     * install, so an update follows the branch head.
     */
    public KitOperationResultDto updateInstalled(
            String tenantId, String projectId, String kitId, boolean prune,
            @Nullable String token, @Nullable String vaultPassword,
            @Nullable String actor, SettingWriteOrigin origin) {
        requireProject(tenantId, projectId);
        KitInstalledRecordDto record = requireInstalled(tenantId, projectId, kitId);
        return importKit(tenantId, updateRequestFor(record, projectId, prune, token, vaultPassword),
                actor, origin);
    }

    /**
     * Re-run every installed kit of a project, in layer order so the
     * result on disk matches what the ordering promises.
     *
     * <p>One failing kit does not abort the rest: with several kits
     * installed, an unreachable repo or a broken new version would
     * otherwise block updates of everything behind it. Failures surface
     * as a warning entry on that kit's result.
     */
    public List<KitOperationResultDto> updateAllInstalled(
            String tenantId, String projectId, boolean prune,
            @Nullable String token, @Nullable String vaultPassword,
            @Nullable String actor, SettingWriteOrigin origin) {
        requireProject(tenantId, projectId);
        List<KitOperationResultDto> results = new ArrayList<>();
        for (KitInstalledRecordDto record : recordStore.listInLayerOrder(tenantId, projectId)) {
            try {
                results.add(importKit(tenantId,
                        updateRequestFor(record, projectId, prune, token, vaultPassword),
                        actor, origin));
            } catch (KitException e) {
                log.warn("KitService: update of kit '{}' in {}/{} failed: {}",
                        record.getId(), tenantId, projectId, e.getMessage());
                results.add(KitOperationResultDto.builder()
                        .kitName(record.getKit().getName())
                        .kitId(record.getId())
                        .mode(KitImportMode.UPDATE.name())
                        .warnings(List.of("update failed: " + e.getMessage()))
                        .build());
            }
        }
        return results;
    }

    private static KitImportRequestDto updateRequestFor(
            KitInstalledRecordDto record, String projectId, boolean prune,
            @Nullable String token, @Nullable String vaultPassword) {
        KitInheritDto source = KitInheritDto.builder()
                .url(record.getOrigin().getUrl())
                .path(record.getOrigin().getPath())
                .branch(record.getOrigin().getBranch())
                .build();
        return KitImportRequestDto.builder()
                .projectId(projectId)
                .source(source)
                .mode(KitImportMode.UPDATE)
                .prune(prune)
                .token(token)
                .vaultPassword(vaultPassword)
                .build();
    }

    /**
     * Re-apply every installed kit at the version each one already has,
     * in layer order.
     *
     * <p>Repairs the on-disk state after the layer order changed or after
     * a kit stopped shipping an artefact a lower kit still owns: the
     * artefact is then left holding the higher kit's old content, and
     * only writing the layers out in order again fixes it. Uses each
     * record's <b>pinned commit</b>, so this changes what is on disk but
     * never which version is installed — that is what update is for.
     *
     * <p>Local edits stay safe: the ordinary policy applies, and a
     * sibling kit's content is no longer mistaken for a user edit
     * (see {@code KitPolicy.decide}).
     */
    public List<KitOperationResultDto> reapplyAll(
            String tenantId, String projectId, @Nullable String token,
            @Nullable String vaultPassword, @Nullable String actor, SettingWriteOrigin origin) {
        requireProject(tenantId, projectId);
        List<KitOperationResultDto> results = new ArrayList<>();
        for (KitInstalledRecordDto record : recordStore.listInLayerOrder(tenantId, projectId)) {
            try {
                results.add(importKit(tenantId,
                        reapplyRequestFor(record, projectId, token, vaultPassword),
                        actor, origin));
            } catch (KitException e) {
                log.warn("KitService: reapply of kit '{}' in {}/{} failed: {}",
                        record.getId(), tenantId, projectId, e.getMessage());
                results.add(KitOperationResultDto.builder()
                        .kitName(record.getKit().getName())
                        .kitId(record.getId())
                        .mode(KitImportMode.UPDATE.name())
                        .warnings(List.of("reapply failed: " + e.getMessage()))
                        .build());
            }
        }
        return results;
    }

    /**
     * Unlike {@link #updateRequestFor}, this one <b>keeps</b> the pinned
     * commit — reapply is about the order things were written in, not
     * about fetching anything newer.
     */
    private static KitImportRequestDto reapplyRequestFor(
            KitInstalledRecordDto record, String projectId,
            @Nullable String token, @Nullable String vaultPassword) {
        return KitImportRequestDto.builder()
                .projectId(projectId)
                .source(KitInheritDto.builder()
                        .url(record.getOrigin().getUrl())
                        .path(record.getOrigin().getPath())
                        .branch(record.getOrigin().getBranch())
                        .commit(record.getOrigin().getCommit())
                        .build())
                .mode(KitImportMode.UPDATE)
                .prune(false)
                .token(token)
                .vaultPassword(vaultPassword)
                .build();
    }

    /**
     * Remove one installed kit. Without {@code prune} this only forgets
     * the record — the artefacts stay, because the user may well have
     * built on them. With {@code prune} the artefacts go too, except
     * those another installed kit also owns.
     */
    public KitOperationResultDto uninstall(
            String tenantId, String projectId, String kitId, boolean prune) {
        requireProject(tenantId, projectId);
        return installer.uninstall(
                tenantId, projectId, requireInstalled(tenantId, projectId, kitId), prune);
    }

    /**
     * Apply a tool-template kit — one that ships a {@code template.yaml}
     * sibling of {@code kit.yaml}. The supplied {@code inputs} are
     * validated against the template's input schema, {@code {{var:X}}}
     * placeholders in the kit's documents are substituted in place, and
     * any input with {@code target.kind=setting} is persisted via
     * {@link de.mhus.vance.shared.settings.SettingService} (PASSWORD
     * inputs encrypted at rest).
     *
     * <p>Mode is always {@link KitImportMode#APPLY} — templates are
     * artifact-style by design (no tracking, idempotent re-apply with new
     * inputs is the supported update path).
     *
     * @return result wrapping the underlying installer outcome plus the
     *         template's {@code postInstall} hook for the caller to surface
     */
    public TemplateApplier.ApplyResult applyTemplate(
            String tenantId,
            String projectId,
            KitInheritDto source,
            java.util.Map<String, String> inputs,
            @Nullable String token,
            @Nullable String actor,
            SettingWriteOrigin origin) {
        requireProject(tenantId, projectId);
        KitResolver.ResolvedKit resolved = null;
        try {
            resolved = resolver.resolve(
                    storeCredentials.resolve(
                            tenantId, projectId, actor, source.getUrl(), token),
                    source);
            // Templates are by definition artifact-style; reject any
            // attempt to track them in a manifest.
            if (!resolved.topLayer().isArtifact()) {
                log.warn("KitService.applyTemplate: top-layer '{}' is not marked artifact:true — "
                        + "applying as-if-artifact (no manifest written)",
                        resolved.topLayer().getName());
            }
            return templateApplier.applyTemplate(
                    tenantId, projectId, source, resolved, inputs, actor, origin);
        } finally {
            if (resolved != null) resolved.cleanup(workspace);
        }
    }

    // ──────────────────── authoring ────────────────────

    /**
     * Export the project's kit source back to a git remote. Uses the
     * authoring manifest's {@code origin} for url/path/branch defaults
     * when {@link KitExportRequestDto} fields are blank.
     */
    public KitOperationResultDto export(
            String tenantId, KitExportRequestDto request, @Nullable String actor) {
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            throw new KitException("export request must carry a projectId");
        }
        requireProject(tenantId, request.getProjectId());
        return exporter.export(tenantId, request.getProjectId(), request, actor);
    }

    /**
     * Turn an installed kit into this project's kit source, so it can be
     * edited here and exported back.
     *
     * <p>Exists because the decision comes late: at install time nobody
     * knows yet that they will end up changing the kit. The record
     * already carries origin, descriptor and per-layer ownership, so this
     * needs neither a re-clone nor a reinstall.
     */
    public KitManifestDto promoteToAuthoring(
            String tenantId, String projectId, String kitId, @Nullable String actor) {
        requireProject(tenantId, projectId);
        KitInstalledRecordDto record = requireInstalled(tenantId, projectId, kitId);
        KitManifestDto existing = recordStore.loadManifest(tenantId, projectId);
        if (existing != null) {
            throw new KitException("project " + projectId + " is already the source of kit '"
                    + existing.getKit().getName() + "' — a project can only be one kit."
                    + " Remove " + KitRecordStore.MANIFEST_PATH + " first.");
        }
        KitDescriptorDto descriptor = record.getDescriptor();
        if (descriptor != null && descriptor.isArtifact()) {
            throw new KitException("kit '" + record.getKit().getName()
                    + "' is marked as artifact — a tuning bundle cannot serve as a kit source");
        }
        KitManifestDto manifest = installer.manifestFromRecord(record);
        recordStore.saveManifest(tenantId, projectId, manifest, actor);
        return manifest;
    }

    // ──────────────────── legacy migration ────────────────────

    /**
     * Convert a pre-multi-kit {@code _vance/kit-manifest.yaml} into an
     * install record. Explicit by design — see {@link KitLegacyMigrator}.
     */
    public KitLegacyMigrator.Result migrateLegacy(
            String tenantId, String projectId, boolean keepAsKitSource,
            @Nullable String actor) {
        requireProject(tenantId, projectId);
        return legacyMigrator.migrate(tenantId, projectId, keepAsKitSource, actor);
    }

    /** True when this project still carries an old single-kit manifest. */
    public boolean hasLegacyManifest(String tenantId, String projectId) {
        requireProject(tenantId, projectId);
        return legacyMigrator.hasLegacyManifest(tenantId, projectId);
    }

    // ──────────────────── user config ────────────────────

    /**
     * The user's config for one installed kit — update policy and layer
     * order. Returns the defaults when no config document exists, which
     * is the normal case.
     */
    public KitConfigDto loadConfig(String tenantId, String projectId, String kitId) {
        requireProject(tenantId, projectId);
        requireInstalled(tenantId, projectId, kitId);
        return recordStore.loadConfig(tenantId, projectId, kitId);
    }

    /**
     * Write the user's config for one installed kit.
     *
     * <p>Deliberately its own verb rather than a field on the install
     * path: the record is machine-generated and rewritten on every
     * update, this is hand-authored and must survive that untouched.
     */
    public void saveConfig(String tenantId, String projectId, String kitId, KitConfigDto config,
            @Nullable String actor) {
        requireProject(tenantId, projectId);
        requireInstalled(tenantId, projectId, kitId);
        recordStore.saveConfig(tenantId, projectId, kitId, config, actor);
    }

    private KitInstalledRecordDto requireInstalled(
            String tenantId, String projectId, String kitId) {
        KitInstalledRecordDto record = recordStore.find(tenantId, projectId, kitId);
        if (record == null) {
            throw new KitException("no installed kit '" + kitId + "' in project " + projectId);
        }
        return record;
    }

    // ──────────────────── status ────────────────────

    /** Every kit installed in the project, in layer order (last one wins on collision). */
    public List<KitInstalledRecordDto> status(String tenantId, String projectId) {
        return recordStore.listInLayerOrder(tenantId, projectId);
    }

    /**
     * The project's authoring manifest, or {@code null} when this project
     * is not a kit source — which is the normal case.
     */
    public @Nullable KitManifestDto authoringManifest(String tenantId, String projectId) {
        return recordStore.loadManifest(tenantId, projectId);
    }

    // ──────────────────── validation ────────────────────

    private void requireProject(String tenantId, String projectId) {
        // The installer writes documents and settings under projectId. Without
        // a ProjectDocument, downstream tools that go through
        // EddieContext.resolveProject would fail with "project not found in
        // tenant" — reject up front rather than leaving a half-configured project.
        if (projectService.findByTenantAndName(tenantId, projectId).isEmpty()) {
            throw new KitException("project '" + projectId
                    + "' does not exist in tenant '" + tenantId + "'");
        }
    }

    private static void validateImport(KitImportRequestDto request) {
        if (request.getProjectId() == null || request.getProjectId().isBlank()) {
            throw new KitException("kit request must carry a projectId");
        }
        KitInheritDto source = request.getSource();
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            throw new KitException("kit request must carry a source url");
        }
        if (request.getMode() == null) {
            throw new KitException("kit request must carry a mode");
        }
    }
}
