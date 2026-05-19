package de.mhus.vance.shared.kit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import de.mhus.vance.shared.document.DocumentDocument;
import de.mhus.vance.shared.document.DocumentService;
import de.mhus.vance.shared.home.HomeBootstrapService;
import de.mhus.vance.shared.tenant.TenantService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectKitsCatalogServiceTest {

    private DocumentService documentService;
    private ProjectKitsCatalogService catalog;

    @BeforeEach
    void setUp() {
        documentService = mock(DocumentService.class);
        catalog = new ProjectKitsCatalogService(documentService);
    }

    @Test
    void load_missingDoc_returnsEmptyCatalog() {
        when(documentService.findByPath(
                "acme", HomeBootstrapService.TENANT_PROJECT_NAME, ProjectKitsCatalogService.CATALOG_PATH))
                .thenReturn(Optional.empty());

        ProjectKitsCatalogDto loaded = catalog.load("acme");

        assertThat(loaded.getVersion()).isEqualTo(ProjectKitsCatalogService.CURRENT_VERSION);
        assertThat(loaded.getKits()).isEmpty();
    }

    @Test
    void save_thenLoad_roundtrip() {
        ProjectKitsCatalogDto input = sampleCatalog();

        // First save -> document does not exist yet.
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.empty());

        catalog.save("acme", input);

        ArgumentCaptor<String> yamlText = ArgumentCaptor.forClass(String.class);
        verify(documentService).createText(
                eq("acme"),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                eq(ProjectKitsCatalogService.CATALOG_PATH),
                eq("Project Kits"),
                isNull(),
                yamlText.capture(),
                isNull());

        // Reset the mocks: now wire the saved YAML back through load().
        DocumentDocument doc = new DocumentDocument();
        doc.setInlineText(yamlText.getValue());
        when(documentService.findByPath(
                "acme", HomeBootstrapService.TENANT_PROJECT_NAME, ProjectKitsCatalogService.CATALOG_PATH))
                .thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yamlText.getValue());

        ProjectKitsCatalogDto loaded = catalog.load("acme");

        assertThat(loaded.getVersion()).isEqualTo(input.getVersion());
        assertThat(loaded.getKits()).hasSize(2);
        ProjectKitEntry first = loaded.getKits().get(0);
        assertThat(first.getName()).isEqualTo("base/research");
        assertThat(first.getTitle()).isEqualTo("Research Base");
        assertThat(first.getDescription()).isEqualTo("Web-search research kit");
        assertThat(first.getSource().getUrl())
                .isEqualTo("https://github.com/mhus/vance-kits.git");
        assertThat(first.getSource().getPath()).isEqualTo("kits/research");
        assertThat(first.getSource().getBranch()).isEqualTo("main");
    }

    @Test
    void save_overwritesExistingDocument() {
        DocumentDocument existing = new DocumentDocument();
        existing.setId("doc-1");
        existing.setInlineText("old");
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(existing));

        catalog.save("acme", sampleCatalog());

        verify(documentService).update(eq("doc-1"), isNull(), isNull(), any(), isNull());
        verify(documentService, never()).createText(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void findByName_returnsMatch_orNull() {
        DocumentDocument doc = new DocumentDocument();
        String yaml = catalog.serialize(sampleCatalog());
        doc.setInlineText(yaml);
        when(documentService.findByPath(any(), any(), any())).thenReturn(Optional.of(doc));
        when(documentService.readContent(doc)).thenReturn(yaml);

        assertThat(catalog.findByName("acme", "base/research"))
                .isNotNull()
                .extracting(ProjectKitEntry::getTitle)
                .isEqualTo("Research Base");
        assertThat(catalog.findByName("acme", "nope")).isNull();
        assertThat(catalog.findByName("acme", " ")).isNull();
    }

    @Test
    void save_rejectsBlankName() {
        ProjectKitsCatalogDto bad = ProjectKitsCatalogDto.builder()
                .version(1)
                .kits(List.of(ProjectKitEntry.builder()
                        .name("")
                        .title("x")
                        .source(KitInheritDto.builder().url("u").build())
                        .build()))
                .build();

        assertThatThrownBy(() -> catalog.save("acme", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank name");
    }

    @Test
    void save_rejectsDuplicateNames() {
        KitInheritDto src = KitInheritDto.builder().url("u").build();
        ProjectKitsCatalogDto bad = ProjectKitsCatalogDto.builder()
                .version(1)
                .kits(List.of(
                        ProjectKitEntry.builder().name("a").title("A").source(src).build(),
                        ProjectKitEntry.builder().name("a").title("B").source(src).build()))
                .build();

        assertThatThrownBy(() -> catalog.save("acme", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void save_rejectsBlankUrl() {
        ProjectKitsCatalogDto bad = ProjectKitsCatalogDto.builder()
                .version(1)
                .kits(List.of(ProjectKitEntry.builder()
                        .name("a")
                        .title("A")
                        .source(KitInheritDto.builder().url(" ").build())
                        .build()))
                .build();

        assertThatThrownBy(() -> catalog.save("acme", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source url");
    }

    @Test
    void save_rejectsUnsupportedVersion() {
        ProjectKitsCatalogDto bad = ProjectKitsCatalogDto.builder()
                .version(2)
                .kits(new ArrayList<>())
                .build();

        assertThatThrownBy(() -> catalog.save("acme", bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void seedFromSystemTenant_skipsForSystemTenantItself() {
        catalog.seedFromSystemTenant(TenantService.SYSTEM_TENANT);

        verify(documentService, never()).findByPath(any(), any(), any());
    }

    @Test
    void seedFromSystemTenant_skipsWhenTargetAlreadyHasCatalog() {
        DocumentDocument source = new DocumentDocument();
        source.setInlineText("kits: []\nversion: 1\n");
        DocumentDocument target = new DocumentDocument();
        target.setInlineText("kits: []\nversion: 1\n");

        when(documentService.findByPath(eq(TenantService.SYSTEM_TENANT), any(), any()))
                .thenReturn(Optional.of(source));
        when(documentService.findByPath(eq("acme"), any(), any()))
                .thenReturn(Optional.of(target));
        lenient().when(documentService.readContent(any())).thenReturn("kits: []\nversion: 1\n");

        catalog.seedFromSystemTenant("acme");

        verify(documentService, never())
                .createText(eq("acme"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void seedFromSystemTenant_copiesYamlVerbatimToTarget() {
        String yaml = "version: 1\nkits:\n  - name: x\n    title: X\n    source:\n      url: u\n";
        DocumentDocument source = new DocumentDocument();
        source.setInlineText(yaml);

        when(documentService.findByPath(eq(TenantService.SYSTEM_TENANT), any(), any()))
                .thenReturn(Optional.of(source));
        when(documentService.findByPath(eq("acme"), any(), any()))
                .thenReturn(Optional.empty());
        when(documentService.readContent(source)).thenReturn(yaml);

        catalog.seedFromSystemTenant("acme");

        verify(documentService).createText(
                eq("acme"),
                eq(HomeBootstrapService.TENANT_PROJECT_NAME),
                eq(ProjectKitsCatalogService.CATALOG_PATH),
                eq("Project Kits"),
                isNull(),
                eq(yaml),
                isNull());
    }

    @Test
    void seedFromSystemTenant_silentSkipOnTargetProjectMissing() {
        String yaml = "version: 1\nkits: []\n";
        DocumentDocument source = new DocumentDocument();
        source.setInlineText(yaml);

        when(documentService.findByPath(eq(TenantService.SYSTEM_TENANT), any(), any()))
                .thenReturn(Optional.of(source));
        when(documentService.findByPath(eq("acme"), any(), any()))
                .thenReturn(Optional.empty());
        when(documentService.readContent(source)).thenReturn(yaml);
        when(documentService.createText(eq("acme"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("project '_vance' not provisioned yet"));

        // Should not propagate — seed is best-effort.
        catalog.seedFromSystemTenant("acme");
    }

    private static ProjectKitsCatalogDto sampleCatalog() {
        return ProjectKitsCatalogDto.builder()
                .version(1)
                .kits(List.of(
                        ProjectKitEntry.builder()
                                .name("base/research")
                                .title("Research Base")
                                .description("Web-search research kit")
                                .source(KitInheritDto.builder()
                                        .url("https://github.com/mhus/vance-kits.git")
                                        .path("kits/research")
                                        .branch("main")
                                        .build())
                                .build(),
                        ProjectKitEntry.builder()
                                .name("base/dev/java")
                                .title("Java Development")
                                .source(KitInheritDto.builder()
                                        .url("https://github.com/mhus/vance-kits.git")
                                        .path("kits/dev-java")
                                        .branch("main")
                                        .build())
                                .build()))
                .build();
    }
}
