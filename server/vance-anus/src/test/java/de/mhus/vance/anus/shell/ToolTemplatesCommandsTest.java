package de.mhus.vance.anus.shell;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.anus.shell.ToolTemplatesCommands.MergeResult;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolTemplatesCommandsTest {

    @Test
    void merge_keepsTenantOnlyEntries_andAddsNewOnes() {
        ToolTemplateCatalogDto existing = catalog(
                entry("jira", "Atlassian Jira", "developer-tools", "u1", "tools/jira", "main"),
                entry("tenant/custom", "Custom", null, "u2", "tools/custom", "main"));
        ToolTemplateCatalogDto scanned = catalog(
                entry("jira", "Atlassian Jira", "developer-tools", "u1", "tools/jira", "main"),
                entry("smtp-sender", "SMTP", "communication", "u3", "tools/smtp", "main"));

        MergeResult merged = ToolTemplatesCommands.merge(scanned, existing);

        assertThat(merged.added()).containsExactly("smtp-sender");
        assertThat(merged.updated()).isEmpty();
        assertThat(merged.kept()).containsExactly("tenant/custom");
        assertThat(merged.removed()).isEmpty();
        assertThat(merged.result().getTemplates())
                .extracting(ToolTemplateCatalogEntry::getName)
                .containsExactly("jira", "tenant/custom", "smtp-sender");
    }

    @Test
    void merge_updatesEntryWhenTitleChanges() {
        ToolTemplateCatalogDto existing = catalog(
                entry("jira", "Old Title", "developer-tools", "u1", "tools/jira", "main"));
        ToolTemplateCatalogDto scanned = catalog(
                entry("jira", "New Title", "developer-tools", "u1", "tools/jira", "main"));

        MergeResult merged = ToolTemplatesCommands.merge(scanned, existing);

        assertThat(merged.updated()).containsExactly("jira");
        assertThat(merged.added()).isEmpty();
        assertThat(merged.result().getTemplates().get(0).getTitle()).isEqualTo("New Title");
    }

    @Test
    void merge_updatesEntryWhenCategoryChanges() {
        ToolTemplateCatalogDto existing = catalog(
                entry("jira", "Jira", "tools", "u1", "tools/jira", "main"));
        ToolTemplateCatalogDto scanned = catalog(
                entry("jira", "Jira", "developer-tools", "u1", "tools/jira", "main"));

        MergeResult merged = ToolTemplatesCommands.merge(scanned, existing);

        assertThat(merged.updated()).containsExactly("jira");
        assertThat(merged.result().getTemplates().get(0).getCategory())
                .isEqualTo("developer-tools");
    }

    @Test
    void overwrite_replacesCatalogAndReportsRemovals() {
        ToolTemplateCatalogDto existing = catalog(
                entry("jira", "Jira", "developer-tools", "u1", "tools/jira", "main"),
                entry("tenant/custom", "Custom", null, "u2", "tools/custom", "main"));
        ToolTemplateCatalogDto scanned = catalog(
                entry("jira", "Jira v2", "developer-tools", "u1", "tools/jira", "main"),
                entry("smtp-sender", "SMTP", "communication", "u3", "tools/smtp", "main"));

        MergeResult merged = ToolTemplatesCommands.overwrite(scanned, existing);

        assertThat(merged.added()).containsExactly("smtp-sender");
        assertThat(merged.updated()).containsExactly("jira");
        assertThat(merged.kept()).isEmpty();
        assertThat(merged.removed()).containsExactly("tenant/custom");
        assertThat(merged.result().getTemplates())
                .extracting(ToolTemplateCatalogEntry::getName)
                .containsExactly("jira", "smtp-sender");
    }

    @Test
    void overwrite_onEmptyTenant_addsEverythingAsNew() {
        ToolTemplateCatalogDto existing = catalog();
        ToolTemplateCatalogDto scanned = catalog(
                entry("jira", "Jira", "developer-tools", "u", "tools/jira", "main"));

        MergeResult merged = ToolTemplatesCommands.overwrite(scanned, existing);

        assertThat(merged.added()).containsExactly("jira");
        assertThat(merged.removed()).isEmpty();
        assertThat(merged.kept()).isEmpty();
    }

    // ──────────────────── helpers ────────────────────

    private static ToolTemplateCatalogDto catalog(ToolTemplateCatalogEntry... entries) {
        List<ToolTemplateCatalogEntry> list = new ArrayList<>();
        for (ToolTemplateCatalogEntry e : entries) list.add(e);
        return ToolTemplateCatalogDto.builder().version(1).templates(list).build();
    }

    private static ToolTemplateCatalogEntry entry(
            String name, String title, String category, String url, String path, String branch) {
        return ToolTemplateCatalogEntry.builder()
                .name(name)
                .title(title)
                .category(category)
                .source(KitInheritDto.builder().url(url).path(path).branch(branch).build())
                .build();
    }
}
