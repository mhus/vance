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
        config.getConversationAudit().setEnabled(true);
        config.getConversationAudit().setDir("my-logs");

        store.save(tempDir, config);
        assertThat(Files.exists(tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE))).isTrue();

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getConversationAudit().isEnabled()).isTrue();
        assertThat(loaded.get().getConversationAudit().getDir()).isEqualTo("my-logs");
    }

    @Test
    void load_partialYaml_keepsDefaultsForAbsentFields() throws Exception {
        // A config.yaml with only the enabled flag set — dir should
        // default to null (which the service resolves to "conversations").
        String yaml = "conversationAudit:\n  enabled: true\n";
        Path file = tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE);
        Files.writeString(file, yaml);

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getConversationAudit().isEnabled()).isTrue();
        assertThat(loaded.get().getConversationAudit().getDir()).isNull();
    }

    @Test
    void load_unknownFieldsIgnored() throws Exception {
        String yaml = "conversationAudit:\n  enabled: true\n"
                + "futureField: hello\n"
                + "anotherSection:\n  foo: bar\n";
        Path file = tempDir.resolve(VanceProjectConfigStore.CONFIG_FILE);
        Files.writeString(file, yaml);

        var loaded = store.load(tempDir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getConversationAudit().isEnabled()).isTrue();
    }

    @Test
    void file_returnsConfigYamlPath() {
        assertThat(store.file(tempDir)).isEqualTo(tempDir.resolve("config.yaml"));
    }
}
