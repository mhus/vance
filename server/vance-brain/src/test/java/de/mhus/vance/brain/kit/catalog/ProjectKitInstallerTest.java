package de.mhus.vance.brain.kit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitImportMode;
import de.mhus.vance.api.kit.KitImportRequestDto;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitOperationResultDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.brain.kit.KitException;
import de.mhus.vance.brain.kit.KitService;
import de.mhus.vance.shared.kit.catalog.ProjectKitsCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectKitInstallerTest {

    private ProjectKitsCatalogService catalogService;
    private KitService kitService;
    private ProjectKitInstaller installer;

    @BeforeEach
    void setUp() {
        catalogService = mock(ProjectKitsCatalogService.class);
        kitService = mock(KitService.class);
        installer = new ProjectKitInstaller(catalogService, kitService);
    }

    @Test
    void installFromCatalog_blankKitName_isNoOp() {
        assertThat(installer.installFromCatalog("t", "p", null, "actor")).isNull();
        assertThat(installer.installFromCatalog("t", "p", "  ", "actor")).isNull();

        verify(catalogService, never()).findByName(any(), any());
        verify(kitService, never()).importKit(any(), any(), any());
    }

    @Test
    void installFromCatalog_unknownEntry_throwsKitException() {
        when(catalogService.findByName("acme", "missing")).thenReturn(null);

        assertThatThrownBy(() -> installer.installFromCatalog("acme", "p", "missing", "actor"))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not in tenant 'acme' catalog");

        verify(kitService, never()).importKit(any(), any(), any());
    }

    @Test
    void installFromCatalog_delegatesToKitService_withInstallMode() {
        KitInheritDto source = KitInheritDto.builder()
                .url("https://github.com/mhus/vance-kits.git")
                .path("kits/research")
                .branch("main")
                .build();
        ProjectKitEntry entry = ProjectKitEntry.builder()
                .name("base/research")
                .title("Research Base")
                .source(source)
                .build();
        when(catalogService.findByName("acme", "base/research")).thenReturn(entry);
        KitOperationResultDto opResult = mock(KitOperationResultDto.class);
        when(kitService.importKit(eq("acme"), any(), eq("actor"))).thenReturn(opResult);

        KitOperationResultDto returned = installer.installFromCatalog(
                "acme", "new-project", "base/research", "actor");

        assertThat(returned).isSameAs(opResult);
        ArgumentCaptor<KitImportRequestDto> reqCap = ArgumentCaptor.forClass(KitImportRequestDto.class);
        verify(kitService).importKit(eq("acme"), reqCap.capture(), eq("actor"));
        KitImportRequestDto sent = reqCap.getValue();
        assertThat(sent.getProjectId()).isEqualTo("new-project");
        assertThat(sent.getMode()).isEqualTo(KitImportMode.INSTALL);
        assertThat(sent.getSource()).isSameAs(source);
    }
}
