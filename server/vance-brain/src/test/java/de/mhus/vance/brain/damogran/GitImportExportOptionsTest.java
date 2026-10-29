package de.mhus.vance.brain.damogran;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.mhus.vance.brain.damogran.DamogranManifest.ExportEntry;
import de.mhus.vance.brain.damogran.DamogranManifest.ImportEntry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitImportExportOptionsTest {

    private final DamogranContext ctx = mock(DamogranContext.class);

    @Test
    void importRefusesTheRetiredCredentialAlias() {
        ImportEntry entry = new ImportEntry("git:https://h/r.git", "repo", Map.of("credentialAlias", "x"));

        assertThatThrownBy(() -> new GitImporter().doImport(ctx, entry))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("credentialAlias")
                .hasMessageContaining("public");
    }

    @Test
    void exportRefusesTheRetiredCredentialAlias() {
        ExportEntry entry = new ExportEntry("repo", "git:https://h/r.git", Map.of("credentialAlias", "x"));

        assertThatThrownBy(() -> new GitExporter().doExport(ctx, entry))
                .isInstanceOf(DamogranException.class)
                .hasMessageContaining("credentialAlias");
    }
}
