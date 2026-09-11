package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitAuthoringValidationDto;
import de.mhus.vance.api.kit.KitDescriptorDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitManifestDto;
import de.mhus.vance.api.kit.KitMetadataDto;
import de.mhus.vance.api.kit.KitOriginDto;
import de.mhus.vance.api.settings.SettingType;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.settings.SettingDocument;
import de.mhus.vance.shared.settings.SettingService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link KitAuthoringValidator} — the pre-flight check of the creator's
 * kit-authoring flow. Every assertion is a sentence the creator model
 * would otherwise have to learn the hard way, on a failed export.
 */
class KitAuthoringValidatorTest {

    private static final String TENANT = "t1";
    private static final String PROJECT = "p1";

    private KitRecordStore recordStore;
    private DocumentService documentService;
    private SettingService settingService;
    private KitAuthoringValidator validator;

    @BeforeEach
    void setUp() {
        recordStore = mock(KitRecordStore.class);
        documentService = mock(DocumentService.class);
        settingService = mock(SettingService.class);
        validator = new KitAuthoringValidator(recordStore, documentService, settingService);

        lenient()
                .when(recordStore.loadManifestStrict(TENANT, PROJECT))
                .thenReturn(KitRecordStore.ManifestLoad.absent());
        lenient()
                .when(recordStore.loadDescriptorStrict(TENANT, PROJECT))
                .thenReturn(KitRecordStore.DescriptorLoad.absent());
        lenient()
                .when(documentService.findByPath(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient()
                .when(settingService.find(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
    }

    // ── not a source ─────────────────────────────────────────────────

    @Test
    void noManifest_reportsInvalidWithGuidance() {
        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .singleElement()
                .asString()
                .contains("not a kit source")
                .contains("kit_source_create");
        assertThat(result.getKitName()).isNull();
    }

    @Test
    void malformedManifest_reportsTheParseError() {
        when(recordStore.loadManifestStrict(TENANT, PROJECT))
                .thenReturn(KitRecordStore.ManifestLoad.broken("must have a 'kit' map"));

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .singleElement()
                .asString()
                .contains("malformed")
                .contains("must have a 'kit' map");
    }

    // ── happy path ───────────────────────────────────────────────────

    @Test
    void consistentSource_isValid() {
        stubManifest(manifest(true, List.of("notes/helper.md"), List.of("helper.mode")));
        stubDocument("notes/helper.md");
        stubSetting("helper.mode", SettingType.STRING);
        when(recordStore.loadDescriptorStrict(TENANT, PROJECT))
                .thenReturn(KitRecordStore.DescriptorLoad.ok(descriptor("my-kit")));

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).as("errors: %s", result.getErrors()).isTrue();
        assertThat(result.getKitName()).isEqualTo("my-kit");
        assertThat(result.getDocuments()).isEqualTo(1);
        assertThat(result.getSettings()).isEqualTo(1);
        assertThat(result.getOriginUrl()).isEqualTo("https://git.example/kits/my-kit.git");
        assertThat(result.isHasEncryptedSecrets()).isTrue();
    }

    @Test
    void missingArtefacts_areErrors() {
        stubManifest(manifest(false, List.of("there.md", "gone.md"), List.of("gone.mode")));
        stubDocument("there.md");

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .anySatisfy(e -> assertThat(e).contains("gone.md").contains("does not exist"));
        assertThat(result.getErrors())
                .anySatisfy(e -> assertThat(e).contains("gone.mode").contains("does not exist"));
    }

    @Test
    void kitOwnedPaths_areErrors() {
        // A kit shipping _vance/kits/** would rewrite the records it is
        // judged by — the same reservation the install path enforces.
        stubManifest(manifest(false, List.of("_vance/kits/manifest.yaml"), List.of()));
        // The document does exist — only the reservation may fire, otherwise
        // the count says nothing about which check produced the error.
        stubDocument("_vance/kits/manifest.yaml");

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).singleElement().asString().contains("kit-owned path");
    }

    @Test
    void duplicateArtefacts_areWarnings() {
        stubManifest(manifest(false, List.of("a.md", "a.md"), List.of("k", "k")));
        stubDocument("a.md");
        stubSetting("k", SettingType.STRING);

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("duplicate documents entry"));
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("duplicate settings entry"));
    }

    // ── encrypted-secrets flag ────────────────────────────────────────

    @Test
    void encryptedSettingWithoutTheFlag_isAnError() {
        // The flag is export's only vault gate: a false flag with a
        // PASSWORD setting listed would drop the credential with a log
        // line on the next export.
        stubManifest(manifest(false, List.of(), List.of("api.key")));
        stubSetting("api.key", SettingType.PASSWORD);

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).singleElement().asString().contains("hasEncryptedSecrets is false");
    }

    @Test
    void flagWithoutEncryptedSettings_isAWarning() {
        stubManifest(manifest(true, List.of(), List.of("plain.mode")));
        stubSetting("plain.mode", SettingType.STRING);

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("vault passphrase it does not need"));
    }

    // ── descriptor ────────────────────────────────────────────────────

    @Test
    void malformedDescriptor_isAnError() {
        stubManifest(manifest(false, List.of(), List.of()));
        when(recordStore.loadDescriptorStrict(TENANT, PROJECT))
                .thenReturn(KitRecordStore.DescriptorLoad.broken("bad yaml"));

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors())
                .singleElement()
                .asString()
                .contains("kit.yaml")
                .contains("bad yaml")
                .contains("sealed");
    }

    @Test
    void absentDescriptor_isAWarning() {
        stubManifest(manifest(false, List.of(), List.of()));

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).anySatisfy(w -> assertThat(w).contains("no _vance/kits/kit.yaml"));
    }

    @Test
    void descriptorNameMismatch_isAWarning() {
        stubManifest(manifest(false, List.of(), List.of()));
        when(recordStore.loadDescriptorStrict(TENANT, PROJECT))
                .thenReturn(KitRecordStore.DescriptorLoad.ok(descriptor("stale-name")));

        KitAuthoringValidationDto result = validator.validate(TENANT, PROJECT);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings())
                .anySatisfy(w -> assertThat(w).contains("stale-name").contains("my-kit"));
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static KitManifestDto manifest(boolean hasEncryptedSecrets, List<String> documents, List<String> settings) {
        return KitManifestDto.builder()
                .kit(KitMetadataDto.builder().name("my-kit").description("d").build())
                .origin(KitOriginDto.builder()
                        .url("https://git.example/kits/my-kit.git")
                        .build())
                .documents(documents)
                .settings(settings)
                .inherits(List.of(KitInheritDto.builder().url("https://x/y.git").build()))
                .hasEncryptedSecrets(hasEncryptedSecrets)
                .build();
    }

    private static KitDescriptorDto descriptor(String name) {
        return KitDescriptorDto.builder().name(name).description("d").build();
    }

    private void stubManifest(KitManifestDto manifest) {
        when(recordStore.loadManifestStrict(TENANT, PROJECT)).thenReturn(KitRecordStore.ManifestLoad.ok(manifest));
    }

    private void stubDocument(String path) {
        when(documentService.findByPath(eq(TENANT), eq(PROJECT), eq(path)))
                .thenReturn(Optional.of(mock(de.mhus.vance.shared.document.DocumentDocument.class)));
    }

    private void stubSetting(String key, SettingType type) {
        when(settingService.find(eq(TENANT), eq(SettingService.SCOPE_PROJECT), eq(PROJECT), eq(key)))
                .thenReturn(Optional.of(
                        SettingDocument.builder().key(key).type(type).build()));
    }
}
