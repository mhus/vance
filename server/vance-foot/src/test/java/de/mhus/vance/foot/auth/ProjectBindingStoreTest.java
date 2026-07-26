package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectBindingStoreTest {

    private final ProjectBindingStore store = new ProjectBindingStore();

    @Test
    void load_absentFile_returnsEmpty(@TempDir Path dir) {
        assertThat(store.load(dir)).isEmpty();
    }

    @Test
    void save_thenLoad_roundTrips(@TempDir Path dir) {
        ProjectBinding binding = new ProjectBinding();
        binding.setTenant("acme");
        binding.setProject("my-project");
        binding.setUsername("mike");
        ProjectBinding.Brain brain = new ProjectBinding.Brain();
        brain.setHttpBase("https://brain.example.com");
        brain.setWsBase("wss://brain.example.com");
        binding.setBrain(brain);

        store.save(dir, binding);

        Optional<ProjectBinding> loaded = store.load(dir);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getTenant()).isEqualTo("acme");
        assertThat(loaded.get().getProject()).isEqualTo("my-project");
        assertThat(loaded.get().getUsername()).isEqualTo("mike");
        assertThat(loaded.get().getBrain()).isNotNull();
        assertThat(loaded.get().getBrain().getHttpBase()).isEqualTo("https://brain.example.com");
        assertThat(loaded.get().getBrain().getWsBase()).isEqualTo("wss://brain.example.com");
    }

    @Test
    void save_omitsNullFieldsFromYaml(@TempDir Path dir) throws Exception {
        ProjectBinding binding = new ProjectBinding();
        binding.setTenant("acme");

        store.save(dir, binding);

        String yaml = Files.readString(store.file(dir));
        assertThat(yaml).contains("tenant");
        assertThat(yaml).doesNotContain("project");
        assertThat(yaml).doesNotContain("username");
        assertThat(yaml).doesNotContain("brain");
    }
}
