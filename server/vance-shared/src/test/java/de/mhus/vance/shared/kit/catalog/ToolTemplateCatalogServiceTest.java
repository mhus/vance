package de.mhus.vance.shared.kit.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogDto;
import de.mhus.vance.api.kit.ToolTemplateCatalogEntry;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolTemplateCatalogServiceTest {

    private final ToolTemplateCatalogService svc = new ToolTemplateCatalogService(null);

    @Test
    void serializes_and_round_trips_a_minimal_catalog() {
        ToolTemplateCatalogDto cat = ToolTemplateCatalogDto.builder()
                .version(1)
                .templates(List.of(
                        ToolTemplateCatalogEntry.builder()
                                .name("jira")
                                .title("Atlassian Jira")
                                .description("OAuth + REST")
                                .category("developer-tools")
                                .source(KitInheritDto.builder()
                                        .url("https://github.com/mhus/vance-kits.git")
                                        .path("tools/jira")
                                        .build())
                                .build()))
                .build();

        String yaml = svc.serialize(cat);
        assertThat(yaml).contains("name: jira");
        assertThat(yaml).contains("category: developer-tools");
        assertThat(yaml).contains("path: tools/jira");
        assertThat(yaml).contains("version: 1");
    }

    @Test
    void rejects_save_with_blank_source_url() {
        ToolTemplateCatalogDto cat = ToolTemplateCatalogDto.builder()
                .version(1)
                .templates(List.of(
                        ToolTemplateCatalogEntry.builder()
                                .name("jira")
                                .source(KitInheritDto.builder().url("").build())
                                .build()))
                .build();
        assertThatThrownBy(() -> svc.save("any-tenant", cat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source url");
    }

    @Test
    void rejects_duplicate_template_names() {
        ToolTemplateCatalogDto cat = ToolTemplateCatalogDto.builder()
                .version(1)
                .templates(List.of(
                        ToolTemplateCatalogEntry.builder()
                                .name("dup")
                                .source(KitInheritDto.builder().url("https://x").build())
                                .build(),
                        ToolTemplateCatalogEntry.builder()
                                .name("dup")
                                .source(KitInheritDto.builder().url("https://y").build())
                                .build()))
                .build();
        assertThatThrownBy(() -> svc.save("any-tenant", cat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejects_unsupported_version() {
        ToolTemplateCatalogDto cat = ToolTemplateCatalogDto.builder()
                .version(42)
                .templates(List.of())
                .build();
        assertThatThrownBy(() -> svc.save("any-tenant", cat))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}
