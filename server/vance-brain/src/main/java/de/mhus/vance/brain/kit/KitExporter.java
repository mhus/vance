package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
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

    private final KitInstaller installer;
    private final KitRepoLoader repoLoader;
    private final KitWorkspace workspace;
    private final DocumentService documentService;
    private final SettingService settingService;

    public KitOperationResultDto export(
            String tenantId,
            String projectId,
            KitExportRequestDto request,
            @Nullable String actor) {

        KitManifestDto manifest = installer.loadManifest(tenantId, projectId);
        if (manifest == null) {
            throw new KitException("project " + projectId + " has no active kit to export");
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
            Path kitRoot = subPath == null || subPath.isBlank()
                    ? workTree
                    : workTree.resolve(subPath);
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
            Path file = docsRoot.resolve(path);
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
            if (setting.getType() == SettingType.PASSWORD) {
                if (vaultPassword == null || vaultPassword.isBlank()) {
                    log.warn("skipping password-setting '{}' — no vault password", key);
                    continue;
                }
                exportedValue = settingService.decryptForExport(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key, vaultPassword);
                if (exportedValue == null) {
                    log.warn("failed to re-encrypt password-setting '{}' for export", key);
                    continue;
                }
            } else {
                exportedValue = setting.getValue();
            }
            KitYamlMapper.ParsedSetting parsed = new KitYamlMapper.ParsedSetting(
                    setting.getType(), exportedValue, setting.getDescription());
            String yaml = KitYamlMapper.writeSetting(parsed);
            Path file = settingsRoot.resolve(key + KitInstaller.SETTING_FILE_SUFFIX);
            try {
                Files.writeString(file, yaml, StandardCharsets.UTF_8);
                written.add(key);
            } catch (IOException e) {
                throw new KitException("failed to write " + file, e);
            }
        }
        return written;
    }

    private void writeDescriptor(KitManifestDto manifest, Path kitRoot) {
        KitDescriptorDto descriptor = KitDescriptorDto.builder()
                .name(manifest.getKit().getName())
                .description(manifest.getKit().getDescription())
                .version(manifest.getKit().getVersion())
                .inherits(new ArrayList<>(manifest.getInherits()))
                .hasEncryptedSecrets(manifest.isHasEncryptedSecrets())
                .build();
        Path file = kitRoot.resolve("kit.yaml");
        try {
            Files.writeString(file, KitYamlMapper.writeDescriptor(descriptor),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to write kit.yaml", e);
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
