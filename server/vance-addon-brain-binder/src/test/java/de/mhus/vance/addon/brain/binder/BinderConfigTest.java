package de.mhus.vance.addon.brain.binder;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.shared.document.kind.ApplicationDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BinderConfigTest {

    private static ApplicationDocument withBinderBlock(Map<String, Object> block) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("binder", block);
        return new ApplicationDocument("application", "binder", "T", null, config,
                new LinkedHashMap<>());
    }

    @Test
    void from_parsesEntriesLandingAndIndex() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("landingRef", "vance:/a.yaml");
        block.put("entries", List.of(
                Map.of("ref", "vance:/a.yaml"),
                Map.of("ref", "vance:/b.yaml", "section", "Reports", "title", "B")));
        block.put("index", Map.of("outputPath", "_custom.md"));

        BinderConfig cfg = BinderConfig.from(withBinderBlock(block));

        assertThat(cfg.landingRef()).isEqualTo("vance:/a.yaml");
        assertThat(cfg.indexOutputPath()).isEqualTo("_custom.md");
        assertThat(cfg.entries()).hasSize(2);
        assertThat(cfg.entries().get(1).section()).isEqualTo("Reports");
        assertThat(cfg.entries().get(1).title()).isEqualTo("B");
    }

    @Test
    void from_acceptsBareStringEntries() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("entries", List.of("vance:/a.yaml", "vance:/b.yaml"));

        BinderConfig cfg = BinderConfig.from(withBinderBlock(block));

        assertThat(cfg.entries()).hasSize(2);
        assertThat(cfg.entries().get(0).ref()).isEqualTo("vance:/a.yaml");
    }

    @Test
    void from_missingBlockYieldsEmptyConfigWithDefaults() {
        ApplicationDocument doc = new ApplicationDocument(
                "application", "binder", null, null, new LinkedHashMap<>(), new LinkedHashMap<>());

        BinderConfig cfg = BinderConfig.from(doc);

        assertThat(cfg.entries()).isEmpty();
        assertThat(cfg.landingRef()).isNull();
        assertThat(cfg.indexOutputPath()).isEqualTo("_index.md");
    }

    @Test
    void from_skipsMalformedEntriesWithoutRef() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("entries", List.of(Map.of("section", "X"), Map.of("ref", "vance:/ok.yaml")));

        BinderConfig cfg = BinderConfig.from(withBinderBlock(block));

        assertThat(cfg.entries()).hasSize(1);
        assertThat(cfg.entries().get(0).ref()).isEqualTo("vance:/ok.yaml");
    }
}
