package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitExportRequestDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
// PersonIdent / UsernamePasswordCredentialsProvider / GitAPIException
// moved into GitWriteableTarget alongside the commit/push logic.
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Pushes the active kit's top-layer back to a git remote: opens a clone,
 * has {@link KitTreeWriter} lay the kit into it, commits, pushes.
 *
 * <p>What the tree consists of is not decided here — that is the writer's
 * job, shared with {@link ProjectKitSourceLoader} so that a kit exported to
 * git and the same kit installed straight out of this project are the same
 * kit. This class owns only the git half and the one decision that follows
 * from it: a repository is somewhere the tree comes to <em>rest</em>, so
 * credentials travel {@link KitTreeWriter.SecretMode#VAULT}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitExporter {

    private final KitRecordStore recordStore;
    private final KitRepoLoader repoLoader;
    private final KitWorkspace workspace;
    private final KitTreeWriter treeWriter;

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

            // VAULT: this tree is about to sit in a repository anyone reaching
            // it can clone, which is the one case a vault passphrase is for.
            KitTreeWriter.Written written = treeWriter.write(
                    tenantId, projectId, manifest, kitRoot,
                    KitTreeWriter.SecretMode.VAULT, request.getVaultPassword());
            // Tools are no longer a kit-level concept — they live under
            // documents/server-tools/<name>.yaml and ride the documents
            // writer. The result still reports a tools list for API stability.
            List<String> writtenTools = new ArrayList<>();

            return commitAndPush(clone, manifest, request,
                    written.documents(), written.settings(), writtenTools, branch, actor);
        } finally {
            workspace.remove(clonePath);
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
