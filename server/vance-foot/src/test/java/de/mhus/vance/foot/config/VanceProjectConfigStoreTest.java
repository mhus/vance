package de.mhus.vance.foot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanceProjectConfigStoreTest {

    @TempDir
    Path tempDir;

    private final VanceProjectConfigStore store = new VanceProjectConfigStore();

    @Test
    void load_absentFile_returnsEmpty() {
        assertThat(store.load(tempDir)).isEmpty();
    }

    @Test
    void saveThenLoad_roundTrips() {
        VanceProjectConfig config = new VanceProjectConfig();
        config.getConversationCapture().setEnabled(true);
        config.getConversationCapture().setDir("my-logs");
        config.getDefaults().setIntellijClaude(true);
        config.getDefaults().setIntellijMcpDefault(true);
        config.getDefaults().setRecipe("coding");
        config.getDefaults().setSandbox(false);

        store.save(tempDir, config);
        assertThat(Files.exists(tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE))).isTrue();

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getConversationCapture().isEnabled()).isTrue();
        assertThat(loaded.get().getConversationCapture().getDir()).isEqualTo("my-logs");
        assertThat(loaded.get().getDefaults().isIntellijClaude()).isTrue();
        assertThat(loaded.get().getDefaults().isIntellijMcpDefault()).isTrue();
        assertThat(loaded.get().getDefaults().getRecipe()).isEqualTo("coding");
        assertThat(loaded.get().getDefaults().isSandbox()).isFalse();
    }

    @Test
    void load_partialYaml_keepsDefaultsForAbsentFields() throws Exception {
        // A config.yaml with only the enabled flag set — dir should
        // default to null (which the service resolves to "conversations").
        String yaml = "conversationCapture:\n  enabled: true\n";
        Path file = tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE);
        Files.writeString(file, yaml);

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getConversationCapture().isEnabled()).isTrue();
        assertThat(loaded.get().getConversationCapture().getDir()).isNull();
    }

    @Test
    void load_unknownFieldsIgnored() throws Exception {
        String yaml = "conversationCapture:\n  enabled: true\n"
                + "futureField: hello\n"
                + "anotherSection:\n  foo: bar\n";
        Path file = tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE);
        Files.writeString(file, yaml);

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getConversationCapture().isEnabled()).isTrue();
    }

    @Test
    void load_defaultsSection_roundTrips() throws Exception {
        String yaml = "conversationCapture:\n  enabled: true\n"
                + "defaults:\n"
                + "  intellijClaude: true\n"
                + "  intellijMcpDefault: true\n"
                + "  recipe: coding\n"
                + "  sandbox: false\n";
        Path file = tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE);
        Files.writeString(file, yaml);

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getDefaults().isIntellijClaude()).isTrue();
        assertThat(loaded.get().getDefaults().isIntellijMcpDefault()).isTrue();
        assertThat(loaded.get().getDefaults().getRecipe()).isEqualTo("coding");
        assertThat(loaded.get().getDefaults().isSandbox()).isFalse();
    }

    @Test
    void file_returnsConfigYamlPath() {
        assertThat(store.file(tempDir)).isEqualTo(tempDir.resolve("config.yaml"));
    }
}
