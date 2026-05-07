package de.mhus.vance.foot.tools.pack;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests for {@link FootToolPackLoader}. Uses a temp directory pointed
 * at via the {@code vance.foot.tools.dir} mechanism (set via
 * reflection — full Spring context isn't worth the boot cost).
 */
class FootToolPackLoaderTest {

    private Path dir;
    private FootToolPackLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("foot-tool-pack-test-");
        ObjectMapper mapper = JsonMapper.builder().build();
        loader = new FootToolPackLoader(mapper);
        ReflectionTestUtils.setField(loader, "configuredDir", dir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dir != null) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        }
    }

    @Test
    void emptyDirectory_returnsEmptyList() {
        assertThat(loader.loadAll()).isEmpty();
    }

    @Test
    void parsesValidJsonFile() throws IOException {
        Files.writeString(dir.resolve("jira.json"), """
                {
                  "name": "jira",
                  "type": "rest_api",
                  "description": "JIRA REST API",
                  "primary": false,
                  "defaultDeferred": true,
                  "labels": ["external", "jira"],
                  "parameters": {
                    "specUrl": "http://example.com/openapi.json"
                  }
                }
                """);

        List<FootToolPackConfig> configs = loader.loadAll();

        assertThat(configs).hasSize(1);
        FootToolPackConfig c = configs.get(0);
        assertThat(c.name()).isEqualTo("jira");
        assertThat(c.type()).isEqualTo("rest_api");
        assertThat(c.defaultDeferred()).isTrue();
        assertThat(c.labels()).containsExactly("external", "jira");
        assertThat(c.parameters()).containsKey("specUrl");
        assertThat(c.isEffectivelyEnabled()).isTrue();
    }

    @Test
    void disabledViaJsonField_isFlagged() throws IOException {
        Files.writeString(dir.resolve("disabled.json"), """
                {
                  "name": "disabled-pack",
                  "type": "rest_api",
                  "enabled": false,
                  "parameters": { "specUrl": "http://x" }
                }
                """);

        List<FootToolPackConfig> configs = loader.loadAll();

        assertThat(configs).hasSize(1);
        assertThat(configs.get(0).isEffectivelyEnabled()).isFalse();
    }

    @Test
    void disabledViaFilenameSuffix_isSkipped() throws IOException {
        Files.writeString(dir.resolve("active.json"), """
                {"name":"active","type":"rest_api","parameters":{"specUrl":"http://x"}}
                """);
        Files.writeString(dir.resolve("inactive.json.disabled"), """
                {"name":"inactive","type":"rest_api","parameters":{"specUrl":"http://y"}}
                """);

        List<FootToolPackConfig> configs = loader.loadAll();

        assertThat(configs).extracting(FootToolPackConfig::name).containsExactly("active");
    }

    @Test
    void malformedJsonFile_isSkipped_withoutAffectingOthers() throws IOException {
        Files.writeString(dir.resolve("ok.json"), """
                {"name":"ok","type":"rest_api","parameters":{"specUrl":"http://x"}}
                """);
        Files.writeString(dir.resolve("broken.json"), "this is not json");

        List<FootToolPackConfig> configs = loader.loadAll();

        assertThat(configs).extracting(FootToolPackConfig::name).containsExactly("ok");
    }

    @Test
    void missingNameOrType_isSkipped() throws IOException {
        Files.writeString(dir.resolve("nameless.json"), """
                {"type":"rest_api","parameters":{}}
                """);

        List<FootToolPackConfig> configs = loader.loadAll();

        assertThat(configs).isEmpty();
    }

    @Test
    void multipleFiles_areAllLoaded() throws IOException {
        Files.writeString(dir.resolve("a.json"),
                "{\"name\":\"a\",\"type\":\"rest_api\",\"parameters\":{\"specUrl\":\"http://a\"}}");
        Files.writeString(dir.resolve("b.json"),
                "{\"name\":\"b\",\"type\":\"mcp_server\",\"parameters\":{\"transport\":\"http\",\"url\":\"http://b/mcp\"}}");

        List<FootToolPackConfig> configs = loader.loadAll();

        assertThat(configs).extracting(FootToolPackConfig::name)
                .containsExactlyInAnyOrder("a", "b");
    }
}
