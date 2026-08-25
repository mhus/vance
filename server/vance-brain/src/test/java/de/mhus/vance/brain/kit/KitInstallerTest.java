package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitArtefactDto;
import de.mhus.vance.api.kit.KitArtefactsDto;
import de.mhus.vance.api.kit.KitConfigDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitInstalledRecordDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.kit.KitSignatureStatus;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.brain.servertool.ServerToolRegistry;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentHeader;
import de.mhus.vance.shared.document.DocumentHeaderParser;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.kit.KitException;
import de.mhus.vance.shared.kit.KitHash;
import de.mhus.vance.shared.settings.AgentSettingKeyPolicy;
import de.mhus.vance.shared.settings.KitSettingKeyPolicy;
import de.mhus.vance.shared.settings.SettingService;
import de.mhus.vance.shared.settings.SettingWriteOrigin;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * {@link KitInstaller} — the only class in the kit path that deletes.
 *
 * <p>Two things are pinned here. The <b>build-tree guard</b>: what a kit is
 * flatly not allowed to ship, refused before a single write happens. And the
 * <b>prune path</b>: an artefact leaving a kit's record may be deleted, may
 * belong to a sibling kit, or may simply not have been asked for — three
 * outcomes for one code path, and the wrong one loses a file.
 *
 * <p>Everything is a mock except the build tree itself, which is a real
 * directory: the scan walks the filesystem, so faking it would test nothing.
 */
class KitInstallerTest {

    private static final String TENANT = "acme";
    private static final String PROJECT = "research";
    private static final String KIT_URL = "https://git.example/kits.git";

    @TempDir
    Path buildRoot;

    private DocumentService documentService;
    private DocumentHeaderParser headerParser;
    private SettingService settingService;
    private KitRecordStore recordStore;
    private KitInstaller installer;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        headerParser = mock(DocumentHeaderParser.class);
        settingService = mock(SettingService.class);
        recordStore = mock(KitRecordStore.class);

        installer = new KitInstaller(
                documentService,
                headerParser,
                settingService,
                mock(ServerToolRegistry.class),
                new AgentSettingKeyPolicy("ai.provider.*,vault.*,store.*,kit.*"),
                new KitSettingKeyPolicy("ai.provider.*,vault.*,store.*,kit.*"),
                recordStore,
                mock(KitResolver.class),
                mock(KitWorkspace.class));

        // Neutral defaults — each test overrides only what it is about.
        when(recordStore.loadConfig(any(), any(), any()))
                .thenReturn(KitConfigDto.builder().build());
        when(recordStore.list(any(), any())).thenReturn(new ArrayList<>());
        when(recordStore.loadManifest(any(), any())).thenReturn(null);
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());
        when(headerParser.parse(any(), any())).thenReturn(Optional.empty());
    }

    // ─── build-tree guard ───────────────────────────────────────────────

    @Test
    void install_documentDeclaringPrivileged_isRefusedBeforeAnyWrite() {
        // $meta.privileged is what makes the Ursa loaders honour a runAs:.
        // Kit writes go out as WriteActor.SYSTEM, which passes the
        // DocumentService ADMIN gate by construction — so a kit installed by a
        // project WRITER, or pulled in unattended by a provisioning host, could
        // otherwise plant a scheduler entry running as somebody else.
        writeBuildFile("documents/_vance/scheduler/nightly.yaml",
                "$meta:\n  privileged: true\nrunAs: tenant-admin\n");
        when(headerParser.parse(any(), any())).thenReturn(Optional.of(
                DocumentHeader.builder()
                        .values(new LinkedHashMap<>(Map.of("privileged", "true")))
                        .build()));

        assertThatThrownBy(() -> install(false))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("$meta.privileged")
                .hasMessageContaining("_vance/scheduler/nightly.yaml");
        verify(documentService, never()).createText(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void install_documentDeclaringPrivilegedFalse_isFine() {
        // Only the flag being *set* is the escalation. A kit that spells the
        // field out as false must not be refused.
        writeBuildFile("documents/notes.md", "$meta:\n  privileged: false\n");
        when(headerParser.parse(any(), any())).thenReturn(Optional.of(
                DocumentHeader.builder()
                        .values(new LinkedHashMap<>(Map.of("privileged", "false")))
                        .build()));

        install(false);

        verify(documentService, times(1)).createText(
                eq(TENANT), eq(PROJECT), eq("notes.md"), any(), any(), any(), any(), any());
    }

    @Test
    void install_documentOverwritingTheKitSourceList_isRefused() {
        // kit-sources.yaml decides type, signature policy and public key of
        // every source. A kit that could ship it would switch the signature
        // requirement off for every future install.
        writeBuildFile("documents/_vance/config/kit-sources.yaml",
                "sources:\n  - id: evil\n    type: git\n    url: https://evil.example\n");

        assertThatThrownBy(() -> install(false))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("_vance/config/kit-");
        verify(documentService, never()).createText(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void install_documentUnderTheKitsDirectory_isRefused() {
        writeBuildFile("documents/_vance/kits/installed/forged-000000.yaml", "id: forged\n");

        assertThatThrownBy(() -> install(false))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("_vance/kits/");
    }

    // ─── delivered credentials ──────────────────────────────────────────

    @Test
    void install_plaintextCredential_isWrittenWithoutAVaultPassword() {
        // The whole point of `encoding: plain`. Every install below runs with
        // vaultPassword = null, which is what the provisioning path has and
        // is the reason a delivered credential used to vanish.
        writeBuildFile("settings/hrafnagud.mount.apiKey.yaml", """
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """);

        KitOperationResultDto result = installer.apply(
                access(), PROJECT, source(), resolved(), KitImportMode.INSTALL,
                false, false, /*vaultPassword*/ null, false, SettingWriteOrigin.USER, "alice");

        verify(settingService).setEncryptedSecret(
                eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT),
                eq("hrafnagud.mount.apiKey"), eq("sk-live-abc"), eq(SettingType.PASSWORD));
        assertThat(result.getSkippedPasswords()).isEmpty();
        assertThat(result.getSettingsAdded()).containsExactly("hrafnagud.mount.apiKey");
    }

    @Test
    void install_vaultCredentialWithoutAVaultPassword_isStillSkipped() {
        // The behaviour `encoding: plain` exists to sidestep, pinned so the
        // new branch cannot quietly widen into the old one: a vault blob
        // without its password is unopenable, and pretending otherwise would
        // persist the ciphertext as if it were the secret.
        writeBuildFile("settings/some.apiKey.yaml", """
                type: PASSWORD
                value: "<vault blob>"
                """);

        KitOperationResultDto result = installer.apply(
                access(), PROJECT, source(), resolved(), KitImportMode.INSTALL,
                false, false, /*vaultPassword*/ null, false, SettingWriteOrigin.USER, "alice");

        verify(settingService, never()).setEncryptedSecret(
                any(), any(), any(), any(), any(), any());
        assertThat(result.getSkippedPasswords()).containsExactly("some.apiKey");
    }

    @Test
    void install_plaintextCredential_doesNotReplaceOneAlreadyThere() {
        // A rotated key stays rotated. The delivered value is set once, when
        // the project has none — an install that reset a credential somebody
        // changed would be an outage rather than an update.
        writeBuildFile("settings/hrafnagud.mount.apiKey.yaml", """
                type: PASSWORD
                encoding: plain
                value: "sk-live-abc"
                """);
        when(settingService.exists(
                TENANT, SettingService.SCOPE_PROJECT, PROJECT, "hrafnagud.mount.apiKey"))
                .thenReturn(true);

        install(false);

        verify(settingService, never()).setEncryptedSecret(
                any(), any(), any(), any(), any(), any());
    }

    // ─── prune ──────────────────────────────────────────────────────────

    @Test
    void update_withPrune_deletesAnArtefactTheKitNoLongerShips() {
        writeBuildFile("documents/kept.md", "still here\n");
        recordFor("dropped.md", "kept.md");
        existingDocument("dropped.md", "doc-dropped");

        installer.apply(access(), PROJECT, source(), resolved(), KitImportMode.UPDATE,
                /*prune*/ true, false, null, false, SettingWriteOrigin.USER, "alice");

        verify(documentService).delete(eq("doc-dropped"), any(), any());
    }

    @Test
    void update_withoutPrune_leavesTheArtefactInPlace() {
        // The default is non-destructive: the artefact only drops out of the
        // record. Someone may well have built on it.
        writeBuildFile("documents/kept.md", "still here\n");
        recordFor("dropped.md", "kept.md");
        existingDocument("dropped.md", "doc-dropped");

        installer.apply(access(), PROJECT, source(), resolved(), KitImportMode.UPDATE,
                /*prune*/ false, false, null, false, SettingWriteOrigin.USER, "alice");

        verify(documentService, never()).delete(eq("doc-dropped"), any(), any());
    }

    @Test
    void update_withPrune_leavesAnArtefactAnotherInstalledKitAlsoOwns() {
        // With several kits in a project the same path may belong to another
        // record; deleting it would strip a kit the user never touched.
        writeBuildFile("documents/kept.md", "still here\n");
        KitInstalledRecordDto self = recordFor("shared.md", "kept.md");
        KitInstalledRecordDto sibling = KitInstalledRecordDto.builder()
                .id("other-kit-ffffff")
                .kit(KitMetadataDto.builder().name("other").build())
                .origin(KitOriginDto.builder().url("https://git.example/other.git").build())
                .artefacts(KitArtefactsDto.builder()
                        .documents(List.of(artefact("shared.md", "hash-x")))
                        .settings(new ArrayList<>())
                        .build())
                .build();
        when(recordStore.list(TENANT, PROJECT)).thenReturn(new ArrayList<>(List.of(self, sibling)));
        existingDocument("shared.md", "doc-shared");

        installer.apply(access(), PROJECT, source(), resolved(), KitImportMode.UPDATE,
                /*prune*/ true, false, null, false, SettingWriteOrigin.USER, "alice");

        verify(documentService, never()).delete(eq("doc-shared"), any(), any());
    }

    @Test
    void uninstall_withPrune_removesWhatTheRecordOwnsAndForgetsTheRecord() {
        KitInstalledRecordDto record = recordFor("owned.md");
        existingDocument("owned.md", "doc-owned");

        installer.uninstall(TENANT, PROJECT, record, /*prune*/ true);

        verify(documentService).delete(eq("doc-owned"), any(), any());
        verify(recordStore).delete(TENANT, PROJECT, record.getId());
    }

    // ─── record ─────────────────────────────────────────────────────────

    @Test
    void update_withoutAStampInTheRequest_keepsTheOneOnTheRecord() {
        // buildRecord used to write access.provisioningStamp() unconditionally,
        // so a manual "update all" — which rebuilds the request from the record
        // and carries no stamp — stored null. differs(null, …) is false by
        // contract, which switched the provisioning check off for that kit for
        // good.
        writeBuildFile("documents/kept.md", "still here\n");
        KitInstalledRecordDto previous = recordFor("kept.md");
        previous.getOrigin().setProvisioningStamp("ode:rev-7");

        installer.apply(access(), PROJECT, source(), resolved(), KitImportMode.UPDATE,
                false, false, null, false, SettingWriteOrigin.USER, "alice");

        ArgumentCaptor<KitInstalledRecordDto> saved =
                ArgumentCaptor.forClass(KitInstalledRecordDto.class);
        verify(recordStore).save(eq(TENANT), eq(PROJECT), saved.capture(), any());
        assertThat(saved.getValue().getOrigin().getProvisioningStamp()).isEqualTo("ode:rev-7");
    }

    @Test
    void update_withAStampInTheRequest_writesTheNewOne() {
        writeBuildFile("documents/kept.md", "still here\n");
        KitInstalledRecordDto previous = recordFor("kept.md");
        previous.getOrigin().setProvisioningStamp("ode:rev-7");

        installer.apply(access().withProvisioningStamp("ode:rev-8"), PROJECT, source(),
                resolved(), KitImportMode.UPDATE,
                false, false, null, false, SettingWriteOrigin.USER, "alice");

        ArgumentCaptor<KitInstalledRecordDto> saved =
                ArgumentCaptor.forClass(KitInstalledRecordDto.class);
        verify(recordStore).save(eq(TENANT), eq(PROJECT), saved.capture(), any());
        assertThat(saved.getValue().getOrigin().getProvisioningStamp()).isEqualTo("ode:rev-8");
    }

    @Test
    void install_recordsTheParametersTheSourceWasAskedFor() {
        // The only place they survive. A host that assembles per request
        // cannot be asked the same question again without them, and neither
        // the descriptor nor the commit says what was ordered.
        writeBuildFile("documents/kept.md", "still here\n");

        installer.apply(access().withParams(Map.of("lang", "de")), PROJECT, source(),
                resolved(), KitImportMode.INSTALL,
                false, false, null, false, SettingWriteOrigin.USER, "alice");

        assertThat(savedRecord().getOrigin().getParams()).containsEntry("lang", "de");
    }

    @Test
    void update_withoutParameters_clearsThemInsteadOfMergingTheOldOnes() {
        // Deliberately different from the stamp: an empty stamp means "this
        // path has nothing to say", empty parameters are a statement. Removing
        // the params: block from provisioning.yaml has to take effect —
        // preserving them across a *manual* update is KitService's job, by
        // putting the recorded ones back into the request.
        writeBuildFile("documents/kept.md", "still here\n");
        KitInstalledRecordDto previous = recordFor("kept.md");
        previous.getOrigin().setParams(new java.util.LinkedHashMap<>(Map.of("lang", "de")));

        installer.apply(access(), PROJECT, source(), resolved(), KitImportMode.UPDATE,
                false, false, null, false, SettingWriteOrigin.USER, "alice");

        assertThat(savedRecord().getOrigin().getParams()).isNull();
    }

    // ─── fixtures ───────────────────────────────────────────────────────

    private KitInstalledRecordDto savedRecord() {
        ArgumentCaptor<KitInstalledRecordDto> saved =
                ArgumentCaptor.forClass(KitInstalledRecordDto.class);
        verify(recordStore).save(eq(TENANT), eq(PROJECT), saved.capture(), any());
        return saved.getValue();
    }

    private void install(boolean prune) {
        installer.apply(access(), PROJECT, source(), resolved(), KitImportMode.INSTALL,
                prune, false, null, false, SettingWriteOrigin.USER, "alice");
    }

    private KitAccess access() {
        return KitAccess.of(TENANT, PROJECT);
    }

    private static KitInheritDto source() {
        return KitInheritDto.builder().url(KIT_URL).build();
    }

    private KitResolver.ResolvedKit resolved() {
        KitDescriptorDto top = KitDescriptorDto.builder()
                .name("demo")
                .description("a demo kit")
                .version("1.0.0")
                .build();
        return new KitResolver.ResolvedKit(
                buildRoot,
                top,
                "deadbeef",
                new ArrayList<>(),
                KitResolver.LayerArtefacts.empty(),
                new java.util.LinkedHashMap<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                KitSignatureStatus.UNSIGNED,
                "test-source",
                KitSourceType.GIT);
    }

    /**
     * A previous install record owning {@code paths}, wired into both lookups
     * the installer uses ({@code findByOrigin} for "the previous install of
     * this kit", {@code list} for the sibling scan).
     */
    private KitInstalledRecordDto recordFor(String... paths) {
        List<KitArtefactDto> documents = new ArrayList<>();
        for (String path : paths) documents.add(artefact(path, KitHash.of("whatever")));
        KitInstalledRecordDto record = KitInstalledRecordDto.builder()
                .id("demo-abc123")
                .kit(KitMetadataDto.builder().name("demo").version("1.0.0").build())
                .origin(KitOriginDto.builder()
                        .url(KIT_URL)
                        .installedAt(Instant.now())
                        .build())
                .artefacts(KitArtefactsDto.builder()
                        .documents(documents)
                        .settings(new ArrayList<>())
                        .build())
                .build();
        when(recordStore.findByOrigin(eq(TENANT), eq(PROJECT), eq(KIT_URL), any()))
                .thenReturn(record);
        when(recordStore.list(TENANT, PROJECT)).thenReturn(new ArrayList<>(List.of(record)));
        return record;
    }

    private static KitArtefactDto artefact(String path, String hash) {
        return KitArtefactDto.builder().id(path).hash(hash).layer("demo").build();
    }

    /** Make {@code path} resolve to a document with {@code id}. */
    private void existingDocument(String path, String id) {
        DocumentDocument doc = DocumentDocument.builder()
                .tenantId(TENANT).projectId(PROJECT).path(path)
                .build();
        doc.setId(id);
        when(documentService.findByPath(TENANT, PROJECT, path)).thenReturn(Optional.of(doc));
    }

    private void writeBuildFile(String relative, String content) {
        Path file = buildRoot.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new IllegalStateException("failed to lay out the test build tree", e);
        }
    }
}
