package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
// PersonIdent / UsernamePasswordCredentialsProvider / GitAPIException
// moved into GitWriteableTarget alongside the commit/push logic.
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pushes the active kit's top-layer back to a git remote. Reads only
 * what the manifest tracks (no inherits, no manually-added project
 * artefacts), writes them into a fresh clone, commits, pushes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitExporter {

    private final KitRecordStore recordStore;
    private final KitRepoLoader repoLoader;
    private final KitWorkspace workspace;
    private final DocumentService documentService;
    private final SettingService settingService;

    public KitOperationResultDto export(
            String tenantId,
            String projectId,
            KitExportRequestDto request,
            @Nullable String actor) {

        KitManifestDto manifest = recordStore.loadManifest(tenantId, projectId);
        if (manifest == null) {
            throw new KitException("project " + projectId
                    + " is not marked as a kit source — enable the authoring manifest"
                    + " (promote an installed kit or install with writeManifest) before exporting");
        }

        String url = firstNonBlank(request.getUrl(), manifest.getOrigin().getUrl());
        if (url == null) {
            throw new KitException("export url not provided and not present in manifest");
        }
        String branch = firstNonBlank(request.getBranch(), manifest.getOrigin().getBranch());
        String subPath = firstNonBlank(request.getPath(), manifest.getOrigin().getPath());

        if (manifest.isHasEncryptedSecrets()
                && (request.getVaultPassword() == null || request.getVaultPassword().isBlank())) {
            throw new KitException("vault password is required to export password-settings");
        }

        Path clonePath = workspace.allocate("kit-export");
        try (WriteableTarget clone =
                     repoLoader.openForWrite(url, branch, request.getToken(), clonePath)) {
            Path workTree = clone.workTree();
            // Containment before createDirectories, not after: an unchecked
            // `path: ../../..` used to escape the work tree, and every guard
            // further down compares against docsRoot / settingsRoot, which are
            // derived from this very kitRoot — so they confirmed a location
            // that had already left the repository. Same rule as
            // KitRepoLoader.subPath, which is where the comment in
            // writeDocuments says this is mirrored from.
            Path kitRoot = workTree;
            if (subPath != null && !subPath.isBlank()) {
                kitRoot = workTree.resolve(subPath).normalize();
                if (!kitRoot.startsWith(workTree.normalize())) {
                    throw new KitException("kit path escapes repo root: " + subPath);
                }
            }
            try {
                Files.createDirectories(kitRoot);
            } catch (IOException e) {
                throw new KitException("failed to ensure kit sub-path " + kitRoot, e);
            }

            List<String> writtenDocs = writeDocuments(tenantId, projectId, manifest, kitRoot);
            List<String> writtenSettings = writeSettings(
                    tenantId, projectId, manifest, request.getVaultPassword(), kitRoot);
            // Tools are no longer a kit-level concept — they live under
            // documents/server-tools/<name>.yaml and ride the documents
            // writer. The result still reports a tools list for API stability.
            List<String> writtenTools = new ArrayList<>();
            writeDescriptor(manifest, kitRoot);

            return commitAndPush(clone, manifest, request,
                    writtenDocs, writtenSettings, writtenTools, branch, actor);
        } finally {
            workspace.remove(clonePath);
        }
    }

    // ──────────────────── write tree ────────────────────

    private List<String> writeDocuments(
            String tenantId, String projectId, KitManifestDto manifest, Path kitRoot) {
        Path docsRoot = kitRoot.resolve(KitInstaller.DOCUMENTS_DIR);
        List<String> written = new ArrayList<>();
        for (String path : manifest.getDocuments()) {
            Optional<DocumentDocument> doc =
                    documentService.findByPath(tenantId, projectId, path);
            if (doc.isEmpty()) {
                log.warn("manifest references missing document '{}'", path);
                continue;
            }
            String content = readDocumentText(doc.get());
            // Containment: manifest paths come from _vance/kit-manifest.yaml (a
            // project document), so a `../../../etc/...` entry must not let the
            // export write outside the kit root. Mirror resolveKitPath's guard.
            Path file = docsRoot.resolve(path).normalize();
            if (!file.startsWith(docsRoot)) {
                throw new KitException("manifest document path escapes kit root: " + path);
            }
            try {
                Path parent = file.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(file, content, StandardCharsets.UTF_8);
                written.add(path);
            } catch (IOException e) {
                throw new KitException("failed to write " + file, e);
            }
        }
        return written;
    }

    private String readDocumentText(DocumentDocument doc) {
        String inline = documentService.readContent(doc);
        if (inline != null) return inline;
        try (var in = documentService.loadContent(doc)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to read document " + doc.getPath(), e);
        }
    }

    private List<String> writeSettings(
            String tenantId, String projectId, KitManifestDto manifest,
            @Nullable String vaultPassword, Path kitRoot) {
        Path settingsRoot = kitRoot.resolve(KitInstaller.SETTINGS_DIR);
        try {
            Files.createDirectories(settingsRoot);
        } catch (IOException e) {
            throw new KitException("failed to create " + settingsRoot, e);
        }
        List<String> written = new ArrayList<>();
        for (String key : manifest.getSettings()) {
            Optional<SettingDocument> opt = settingService.find(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            if (opt.isEmpty()) {
                log.warn("manifest references missing setting '{}'", key);
                continue;
            }
            SettingDocument setting = opt.get();
            String exportedValue;
            if (setting.getType().encrypted()) {
                if (vaultPassword == null || vaultPassword.isBlank()) {
                    log.warn("skipping encrypted setting '{}' — no vault password", key);
                    continue;
                }
                exportedValue = settingService.decryptForExport(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key, vaultPassword);
                if (exportedValue == null) {
                    log.warn("failed to re-encrypt encrypted setting '{}' for export", key);
                    continue;
                }
            } else {
                exportedValue = setting.getValue();
            }
            KitYamlMapper.ParsedSetting parsed = new KitYamlMapper.ParsedSetting(
                    setting.getType(), exportedValue, setting.getDescription());
            String yaml = KitYamlMapper.writeSetting(parsed);
            // Containment (see writeDocuments): a manifest setting key must not
            // traverse outside the settings root.
            Path file = settingsRoot.resolve(key + KitInstaller.SETTING_FILE_SUFFIX).normalize();
            if (!file.startsWith(settingsRoot)) {
                throw new KitException("manifest setting key escapes kit root: " + key);
            }
            try {
                Files.writeString(file, yaml, StandardCharsets.UTF_8);
                written.add(key);
            } catch (IOException e) {
                throw new KitException("failed to write " + file, e);
            }
        }
        return written;
    }

    /**
     * Refresh {@code kit.yaml} in the clone — <b>update, not regenerate</b>.
     *
     * <p>The manifest carries name, description, version, inherits and the
     * encrypted-secrets flag, and nothing else. Building a fresh descriptor
     * out of it therefore deleted every other field the author had written:
     * {@code sealed}, {@code installable}, {@code artifact}, {@code policy},
     * {@code vendor}, {@code license}, {@code homepage}, {@code render}. §3.2
     * says those flags travel with the repository — and the export was the one
     * operation that removed them from it, silently, on the way back.
     *
     * <p>So the existing file in the clone is the base and the manifest's five
     * fields are laid on top. An absent or unparseable {@code kit.yaml} (a
     * project promoted to a source in a fresh repository) falls back to the
     * generated form, which is what the manifest can honestly state.
     */
    private void writeDescriptor(KitManifestDto manifest, Path kitRoot) {
        Path file = kitRoot.resolve("kit.yaml");
        KitDescriptorDto descriptor = readExistingDescriptor(file);
        // builder().build(), not new KitDescriptorDto(): the @Builder.Default
        // fields (installable = true) are only applied on the builder path.
        if (descriptor == null) descriptor = KitDescriptorDto.builder().build();
        descriptor.setName(manifest.getKit().getName());
        descriptor.setDescription(manifest.getKit().getDescription());
        descriptor.setVersion(manifest.getKit().getVersion());
        descriptor.setInherits(new ArrayList<>(manifest.getInherits()));
        descriptor.setHasEncryptedSecrets(manifest.isHasEncryptedSecrets());
        try {
            Files.writeString(file, KitYamlMapper.writeDescriptor(descriptor),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to write kit.yaml", e);
        }
    }

    /** The descriptor already in the clone, or null when there is none we can read. */
    private @Nullable KitDescriptorDto readExistingDescriptor(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return KitYamlMapper.parseDescriptor(Files.readString(file));
        } catch (IOException | KitException e) {
            // A broken kit.yaml in the target repo must not block the export —
            // overwriting it with a valid one is the more useful outcome, and
            // the author sees the loss in the diff they are about to review.
            log.warn("KitExporter: existing kit.yaml at {} is unreadable ({}) — "
                    + "writing a freshly generated one", file, e.getMessage());
            return null;
        }
    }

    // ──────────────────── commit + push ────────────────────

    private KitOperationResultDto commitAndPush(
            WriteableTarget clone,
            KitManifestDto manifest,
            KitExportRequestDto request,
            List<String> writtenDocs,
            List<String> writtenSettings,
            List<String> writtenTools,
            @Nullable String branch,
            @Nullable String actor) {

        String message = request.getCommitMessage();
        if (message == null || message.isBlank()) {
            String commitShort = manifest.getOrigin().getCommit() == null
                    ? "" : "@" + shortSha(manifest.getOrigin().getCommit());
            message = "vance-export: " + manifest.getKit().getName() + commitShort;
        }

        Optional<String> pushedSha = clone.commitAndPublish(message, actor);
        log.info("Exported kit '{}' to {} (commit {})",
                manifest.getKit().getName(), request.getUrl(),
                pushedSha.orElse("none"));

        KitOperationResultDto.KitOperationResultDtoBuilder result =
                KitOperationResultDto.builder()
                        .kitName(manifest.getKit().getName())
                        .version(manifest.getKit().getVersion())
                        .mode("EXPORT")
                        .documentsAdded(writtenDocs)
                        .settingsAdded(writtenSettings)
                        .toolsAdded(writtenTools);
        pushedSha.ifPresent(result::sourceCommit);
        return result.build();
    }

    private static @Nullable String firstNonBlank(@Nullable String a, @Nullable String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }
}
