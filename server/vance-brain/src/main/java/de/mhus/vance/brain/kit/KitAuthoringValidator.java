package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitAuthoringValidationDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Checks a project's kit-source setup: the authoring manifest, the
 * descriptor beside it, and the artefacts the manifest claims.
 *
 * <p>Written for the creator's authoring flow — the questions it answers
 * are the ones that make {@code kit_export} (or an install straight from
 * this project via {@code project:<name>}) do what the author intended,
 * asked <em>before</em> the push, when fixing is still cheap:
 *
 * <ul>
 *   <li>does the manifest parse, and is it there at all?</li>
 *   <li>does every listed document and setting actually exist? (a missing
 *       one is silently skipped by the writer, so the exported kit would
 *       be quietly incomplete)</li>
 *   <li>does the manifest claim kit-owned paths? ({@code _vance/kits/**}
 *       is reserved — a kit shipping its own records would rewrite the
 *       rules it is judged by)</li>
 *   <li>is the encrypted-secrets flag honest? {@code export} gates the
 *       vault passphrase on it; a false flag with PASSWORD settings listed
 *       would drop credentials with only a log line, a true flag without
 *       any prompts for a password nobody needs.</li>
 *   <li>is the descriptor parseable and name-consistent with the manifest?
 *       {@code export} lays the manifest's fields over the descriptor, and
 *       a broken one silently degrades to the generated minimum, losing
 *       {@code sealed}, {@code policy}, {@code vendor} and friends.</li>
 * </ul>
 *
 * <p>Read-only by construction — this class never writes, which is also
 * why the tool surface above it can stay READ-gated.
 */
@Service
@RequiredArgsConstructor
public class KitAuthoringValidator {

    private final KitRecordStore recordStore;
    private final DocumentService documentService;
    private final SettingService settingService;

    /**
     * Full check of the project's kit-source state. Reports on a project
     * that is not a kit source at all as invalid with guidance — "how do I
     * become one" is the first question an authoring session asks.
     */
    public KitAuthoringValidationDto validate(String tenantId, String projectId) {
        KitRecordStore.ManifestLoad load = recordStore.loadManifestStrict(tenantId, projectId);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (load.manifest() == null) {
            if (load.parseError() != null) {
                errors.add("manifest at " + KitRecordStore.MANIFEST_PATH + " is malformed: " + load.parseError());
            } else {
                errors.add("project '" + projectId + "' is not a kit source — no "
                        + KitRecordStore.MANIFEST_PATH + ". Create one with "
                        + "kit_source_create (new kit) or kit_promote (installed kit).");
            }
            return KitAuthoringValidationDto.builder()
                    .valid(false)
                    .errors(errors)
                    .warnings(warnings)
                    .build();
        }

        KitManifestDto manifest = load.manifest();
        errors.addAll(artefactProblems(tenantId, projectId, manifest.getDocuments(), manifest.getSettings()));
        warnings.addAll(duplicateArtefactWarnings(manifest));

        // The encrypted-secrets flag is the export form's only signal for
        // "ask for a vault passphrase" — an honest one is what keeps
        // credentials from being dropped or passwords from being demanded
        // without cause.
        boolean anyEncrypted = anyEncryptedSetting(tenantId, projectId, manifest.getSettings());
        if (!manifest.isHasEncryptedSecrets() && anyEncrypted) {
            errors.add("manifest lists PASSWORD-type settings but hasEncryptedSecrets is"
                    + " false — export would skip them silently. Fix the flag in "
                    + KitRecordStore.MANIFEST_PATH + ".");
        } else if (manifest.isHasEncryptedSecrets() && !anyEncrypted) {
            warnings.add("hasEncryptedSecrets is true but none of the listed settings is"
                    + " PASSWORD-typed — the export will ask for a vault passphrase"
                    + " it does not need.");
        }

        validateDescriptor(tenantId, projectId, manifest, errors, warnings);

        return KitAuthoringValidationDto.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .warnings(warnings)
                .kitName(manifest.getKit().getName())
                .kitVersion(manifest.getKit().getVersion())
                .documents(
                        manifest.getDocuments() == null
                                ? 0
                                : manifest.getDocuments().size())
                .settings(
                        manifest.getSettings() == null
                                ? 0
                                : manifest.getSettings().size())
                .hasEncryptedSecrets(manifest.isHasEncryptedSecrets())
                .originUrl(
                        manifest.getOrigin() == null
                                ? null
                                : manifest.getOrigin().getUrl())
                .build();
    }

    /**
     * Problems with the artefacts a manifest (or a create request) claims:
     * missing documents, missing settings, kit-owned paths.
     *
     * <p>Shared between {@link #validate} and the from-scratch create — the
     * create refuses on a non-empty result, so a kit source can never come
     * into existence claiming artefacts that are not there.
     */
    public List<String> artefactProblems(
            String tenantId, String projectId, List<String> documents, List<String> settings) {

        List<String> problems = new ArrayList<>();
        for (String path : orEmpty(documents)) {
            String trimmed = path == null ? "" : path.trim();
            if (trimmed.isEmpty()) {
                problems.add("empty document path in the artefact list");
                continue;
            }
            if (KitRecordStore.isReservedPath(trimmed)) {
                problems.add("document '" + trimmed + "' is a kit-owned path — a kit"
                        + " must not ship its own records or source configuration");
            }
            if (documentService.findByPath(tenantId, projectId, trimmed).isEmpty()) {
                problems.add("document '" + trimmed + "' does not exist in project '" + projectId + "'");
            }
        }
        for (String key : orEmpty(settings)) {
            String trimmed = key == null ? "" : key.trim();
            if (trimmed.isEmpty()) {
                problems.add("empty setting key in the artefact list");
                continue;
            }
            if (settingService
                    .find(tenantId, SettingService.SCOPE_PROJECT, projectId, trimmed)
                    .isEmpty()) {
                problems.add("setting '" + trimmed + "' does not exist in project '" + projectId + "'");
            }
        }
        return problems;
    }

    /** True when any listed setting is of an encrypted type (PASSWORD et al.). */
    public boolean anyEncryptedSetting(String tenantId, String projectId, List<String> keys) {
        for (String key : orEmpty(keys)) {
            String trimmed = key == null ? "" : key.trim();
            if (trimmed.isEmpty()) continue;
            SettingDocument setting = settingService
                    .find(tenantId, SettingService.SCOPE_PROJECT, projectId, trimmed)
                    .orElse(null);
            if (setting != null && setting.getType().encrypted()) {
                return true;
            }
        }
        return false;
    }

    private void validateDescriptor(
            String tenantId, String projectId, KitManifestDto manifest, List<String> errors, List<String> warnings) {
        KitRecordStore.DescriptorLoad load = recordStore.loadDescriptorStrict(tenantId, projectId);
        if (load.parseError() != null) {
            errors.add("descriptor at " + KitRecordStore.DESCRIPTOR_PATH + " is malformed: "
                    + load.parseError() + " — export would fall back to a generated kit.yaml"
                    + " and drop every author field (sealed, installable, policy, …).");
            return;
        }
        if (load.descriptor() == null) {
            warnings.add("no " + KitRecordStore.DESCRIPTOR_PATH + " beside the manifest —"
                    + " export will generate a minimal kit.yaml. Author a descriptor to keep"
                    + " vendor, license, policy, sealed, installable and friends.");
            return;
        }
        KitDescriptorDto descriptor = load.descriptor();
        if (descriptor.getName() != null
                && !descriptor.getName().equals(manifest.getKit().getName())) {
            warnings.add("descriptor names kit '" + descriptor.getName()
                    + "' but the manifest says '" + manifest.getKit().getName()
                    + "' — export lets the manifest win, which makes one of the two stale.");
        }
    }

    private static List<String> duplicateArtefactWarnings(KitManifestDto manifest) {
        List<String> warnings = new ArrayList<>();
        warnings.addAll(duplicatesIn("documents", manifest.getDocuments()));
        warnings.addAll(duplicatesIn("settings", manifest.getSettings()));
        return warnings;
    }

    private static List<String> duplicatesIn(String what, List<String> values) {
        List<String> warnings = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : orEmpty(values)) {
            String trimmed = value == null ? "" : value.trim();
            if (!seen.add(trimmed)) {
                warnings.add("duplicate " + what + " entry '" + trimmed + "'");
            }
        }
        return warnings;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}
