package de.mhus.vance.foot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.config.FootConfig;
import org.junit.jupiter.api.Test;

class ProjectBindingApplierTest {

    private final ProjectBindingApplier applier = new ProjectBindingApplier();

    @Test
    void apply_overlaysNonBlankFieldsAndArmsBootstrap() {
        FootConfig config = new FootConfig();
        ProjectBinding binding = new ProjectBinding();
        ProjectBinding.Brain brain = new ProjectBinding.Brain();
        brain.setHttpBase("https://b.example.com");
        brain.setWsBase("wss://b.example.com");
        binding.setBrain(brain);
        binding.setTenant("globex");
        binding.setUsername("hank");
        binding.setProject("proj-1");

        applier.apply(binding, config);

        assertThat(config.getBrain().getHttpBase()).isEqualTo("https://b.example.com");
        assertThat(config.getBrain().getWsBase()).isEqualTo("wss://b.example.com");
        assertThat(config.getAuth().getTenant()).isEqualTo("globex");
        assertThat(config.getAuth().getUsername()).isEqualTo("hank");
        assertThat(config.getBootstrap().getProjectId()).isEqualTo("proj-1");
    }

    @Test
    void apply_leavesDefaultsWhenFieldsBlank() {
        FootConfig config = new FootConfig();
        String defaultTenant = config.getAuth().getTenant();
        ProjectBinding binding = new ProjectBinding(); // all null

        applier.apply(binding, config);

        assertThat(config.getAuth().getTenant()).isEqualTo(defaultTenant);
        assertThat(config.getBootstrap().getProjectId()).isNull();
    }
}
