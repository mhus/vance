package de.mhus.vance.brain.kit;

import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitSecretEncoding;
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
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Turns a project that <em>is</em> a kit source into a kit tree on disk —
 * {@code kit.yaml}, {@code documents/…}, {@code settings/….yaml}.
 *
 * <p>One writer for two readers, and that is the point: {@link KitExporter}
 * writes this tree into a git clone to push it, and {@link
 * ProjectKitSourceLoader} writes it into a temporary directory so the ordinary
 * install pipeline can consume it. Two copies of this code would be two kit
 * formats that drift, and the whole reason a project can serve as a source is
 * that the format is the same one.
 *
 * <p>Reads only what {@link KitManifestDto} tracks: no inherit layers, no
 * artefacts somebody added to the project by hand. The manifest is the
 * statement of what this kit consists of.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KitTreeWriter {

    private final KitRecordStore recordStore;
    private final DocumentService documentService;
    private final SettingService settingService;

    /**
     * How an encrypted setting's value travels in the written tree.
     *
     * <p>The distinction is not cosmetic — it is the answer to "does this
     * credential come to rest somewhere the reader has to fetch from".
     */
    public enum SecretMode {

        /**
         * Re-encrypt with the caller's vault passphrase and mark the entry
         * {@link KitSecretEncoding#VAULT}. For a tree that goes somewhere it
         * will sit: a git repository anyone reaching it can clone.
         */
        VAULT,

        /**
         * Carry the stored ciphertext verbatim and mark the entry
         * {@link KitSecretEncoding#SERVER}. For a tree that never leaves this
         * deployment, where both ends share the server key — the plaintext is
         * never produced, so it cannot end up in the temporary directory the
         * tree is built in.
         */
        SERVER,

        /** Leave encrypted settings out, and say which ones were left out. */
        SKIP
    }

    /**
     * What was written, and what deliberately was not.
     *
     * @param documents document paths written
     * @param settings setting keys written
     * @param skippedSettings setting keys left out — a credential that is
     *     missing from a kit has to be nameable, or the installed project
     *     simply does not work and nobody can say why
     */
    public record Written(
            List<String> documents,
            List<String> settings,
            List<String> skippedSettings) {}

    /**
     * Write the kit into {@code kitRoot}, which must already exist.
     *
     * @param vaultPassword required by {@link SecretMode#VAULT}, ignored
     *     otherwise
     */
    public Written write(
            String tenantId,
            String projectId,
            KitManifestDto manifest,
            Path kitRoot,
            SecretMode secretMode,
            @Nullable String vaultPassword) {

        List<String> documents = writeDocuments(tenantId, projectId, manifest, kitRoot);
        List<String> settings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        writeSettings(tenantId, projectId, manifest, kitRoot, secretMode, vaultPassword,
                settings, skipped);
        writeDescriptor(tenantId, projectId, manifest, kitRoot);
        return new Written(documents, settings, skipped);
    }

    // ──────────────────── documents ────────────────────

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
            // Containment: manifest paths come from a project document, so a
            // `../../../etc/...` entry must not let the write escape the kit
            // root. Mirrors resolveKitPath's guard.
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

    // ──────────────────── settings ────────────────────

    private void writeSettings(
            String tenantId,
            String projectId,
            KitManifestDto manifest,
            Path kitRoot,
            SecretMode secretMode,
            @Nullable String vaultPassword,
            List<String> written,
            List<String> skipped) {

        Path settingsRoot = kitRoot.resolve(KitInstaller.SETTINGS_DIR);
        try {
            Files.createDirectories(settingsRoot);
        } catch (IOException e) {
            throw new KitException("failed to create " + settingsRoot, e);
        }
        for (String key : manifest.getSettings()) {
            Optional<SettingDocument> opt = settingService.find(
                    tenantId, SettingService.SCOPE_PROJECT, projectId, key);
            if (opt.isEmpty()) {
                log.warn("manifest references missing setting '{}'", key);
                continue;
            }
            SettingDocument setting = opt.get();
            KitYamlMapper.ParsedSetting parsed = setting.getType().encrypted()
                    ? encryptedEntry(tenantId, projectId, key, setting, secretMode, vaultPassword)
                    : new KitYamlMapper.ParsedSetting(
                            setting.getType(), setting.getValue(), setting.getDescription());
            if (parsed == null) {
                skipped.add(key);
                continue;
            }
            String yaml = KitYamlMapper.writeSetting(parsed);
            // Containment, as in writeDocuments.
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
    }

    /** The entry for an encrypted setting, or {@code null} to leave it out. */
    private KitYamlMapper.@Nullable ParsedSetting encryptedEntry(
            String tenantId,
            String projectId,
            String key,
            SettingDocument setting,
            SecretMode secretMode,
            @Nullable String vaultPassword) {

        return switch (secretMode) {
            case SKIP -> null;
            // Verbatim: the value in the row is already a server-key blob, and
            // both ends of this tree read that key. Decrypting to re-encrypt
            // would put the credential in a file on the pod's disk for no gain
            // — the one exposure PLAIN cannot avoid and this mode does.
            case SERVER -> new KitYamlMapper.ParsedSetting(
                    setting.getType(), setting.getValue(), setting.getDescription(),
                    KitSecretEncoding.SERVER);
            case VAULT -> {
                if (vaultPassword == null || vaultPassword.isBlank()) {
                    log.warn("skipping encrypted setting '{}' — no vault password", key);
                    yield null;
                }
                String reEncrypted = settingService.decryptForExport(
                        tenantId, SettingService.SCOPE_PROJECT, projectId, key, vaultPassword);
                if (reEncrypted == null) {
                    log.warn("failed to re-encrypt encrypted setting '{}' for export", key);
                    yield null;
                }
                yield new KitYamlMapper.ParsedSetting(
                        setting.getType(), reEncrypted, setting.getDescription(),
                        KitSecretEncoding.VAULT);
            }
        };
    }

    // ──────────────────── descriptor ────────────────────

    /**
     * Write {@code kit.yaml} — <b>update, not regenerate</b>.
     *
     * <p>The manifest carries name, description, version, inherits and the
     * encrypted-secrets flag, and nothing else. Building a fresh descriptor out
     * of it deletes every other field the author wrote: {@code sealed},
     * {@code installable}, {@code artifact}, {@code policy}, {@code vendor},
     * {@code license}, {@code homepage}, {@code render}.
     *
     * <p>So a base is looked for and the manifest's five fields are laid on top
     * of it. Three places, in this order:
     *
     * <ol>
     *   <li>{@link KitRecordStore#DESCRIPTOR_PATH} in the project — the file
     *       the author actually edits, present whatever the destination looks
     *       like.</li>
     *   <li>a {@code kit.yaml} already sitting at the destination — for a
     *       project that became a source before the descriptor was kept as a
     *       document, the remote file is still the only copy. Always absent
     *       when the destination is a freshly allocated directory.</li>
     *   <li>the generated form, which is what the manifest can honestly
     *       state.</li>
     * </ol>
     */
    private void writeDescriptor(
            String tenantId, String projectId, KitManifestDto manifest, Path kitRoot) {
        Path file = kitRoot.resolve("kit.yaml");
        KitDescriptorDto descriptor = recordStore.loadDescriptor(tenantId, projectId);
        if (descriptor == null) descriptor = readExistingDescriptor(file);
        // builder().build(), not new KitDescriptorDto(): the @Builder.Default
        // fields (installable = true) are only applied on the builder path.
        if (descriptor == null) descriptor = KitDescriptorDto.builder().build();
        descriptor.setName(manifest.getKit().getName());
        descriptor.setDescription(manifest.getKit().getDescription());
        descriptor.setVersion(manifest.getKit().getVersion());
        descriptor.setInherits(new ArrayList<>(manifest.getInherits()));
        descriptor.setHasEncryptedSecrets(manifest.isHasEncryptedSecrets());
        try {
            Files.createDirectories(kitRoot);
            Files.writeString(file, KitYamlMapper.writeDescriptor(descriptor),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new KitException("failed to write kit.yaml", e);
        }
    }

    /** The descriptor already at the destination, or null when unreadable. */
    private @Nullable KitDescriptorDto readExistingDescriptor(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return KitYamlMapper.parseDescriptor(Files.readString(file));
        } catch (IOException | KitException e) {
            // A broken kit.yaml at the destination must not block the write —
            // overwriting it with a valid one is the more useful outcome, and
            // an author sees the loss in the diff they are about to review.
            log.warn("KitTreeWriter: existing kit.yaml at {} is unreadable ({}) — "
                    + "writing a freshly generated one", file, e.getMessage());
            return null;
        }
    }
}
