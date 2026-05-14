package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.anus.shell.ProjectKitsCommands.MergeResult;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ProjectKitEntry;
import de.mhus.vance.api.kit.ProjectKitsCatalogDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectKitsCommandsTest {

    @Test
    void merge_keepsTenantOnlyEntries_andAddsNewOnes() {
        ProjectKitsCatalogDto existing = catalog(
                entry("base/research", "Research", "u1", "kits/research", "main"),
                entry("tenant/custom", "Custom", "u2", "kits/custom", "main"));
        ProjectKitsCatalogDto scanned = catalog(
                entry("base/research", "Research", "u1", "kits/research", "main"),
                entry("base/new", "New Kit", "u3", "kits/new", "main"));

        MergeResult merged = ProjectKitsCommands.merge(scanned, existing);

        assertThat(merged.added()).containsExactly("base/new");
        assertThat(merged.updated()).isEmpty();
        assertThat(merged.kept()).containsExactly("tenant/custom");
        assertThat(merged.removed()).isEmpty();

        // Result: existing order preserved, new appended at end.
        assertThat(merged.result().getKits())
                .extracting(ProjectKitEntry::getName)
                .containsExactly("base/research", "tenant/custom", "base/new");
    }

    @Test
    void merge_updatesEntryWhenTitleChanges() {
        ProjectKitsCatalogDto existing = catalog(
                entry("base/research", "Old Title", "u1", "kits/research", "main"));
        ProjectKitsCatalogDto scanned = catalog(
                entry("base/research", "New Title", "u1", "kits/research", "main"));

        MergeResult merged = ProjectKitsCommands.merge(scanned, existing);

        assertThat(merged.updated()).containsExactly("base/research");
        assertThat(merged.added()).isEmpty();
        assertThat(merged.result().getKits().get(0).getTitle()).isEqualTo("New Title");
    }

    @Test
    void overwrite_replacesCatalogAndReportsRemovals() {
        ProjectKitsCatalogDto existing = catalog(
                entry("base/research", "Research", "u1", "kits/research", "main"),
                entry("tenant/custom", "Custom", "u2", "kits/custom", "main"));
        ProjectKitsCatalogDto scanned = catalog(
                entry("base/research", "Research v2", "u1", "kits/research", "main"),
                entry("base/new", "New", "u3", "kits/new", "main"));

        MergeResult merged = ProjectKitsCommands.overwrite(scanned, existing);

        assertThat(merged.added()).containsExactly("base/new");
        assertThat(merged.updated()).containsExactly("base/research");
        assertThat(merged.kept()).isEmpty();
        assertThat(merged.removed()).containsExactly("tenant/custom");
        assertThat(merged.result().getKits())
                .extracting(ProjectKitEntry::getName)
                .containsExactly("base/research", "base/new");
    }

    @Test
    void overwrite_onEmptyTenant_addsEverythingAsNew() {
        ProjectKitsCatalogDto existing = catalog();
        ProjectKitsCatalogDto scanned = catalog(
                entry("a", "A", "u", "p", "main"));

        MergeResult merged = ProjectKitsCommands.overwrite(scanned, existing);

        assertThat(merged.added()).containsExactly("a");
        assertThat(merged.removed()).isEmpty();
    }

    private static ProjectKitsCatalogDto catalog(ProjectKitEntry... entries) {
        return ProjectKitsCatalogDto.builder()
                .version(1)
                .kits(new ArrayList<>(List.of(entries)))
                .build();
    }

    private static ProjectKitEntry entry(String name, String title, String url, String path, String branch) {
        return ProjectKitEntry.builder()
                .name(name)
                .title(title)
                .source(KitInheritDto.builder()
                        .url(url)
                        .path(path)
                        .branch(branch)
                        .build())
                .build();
    }
}
