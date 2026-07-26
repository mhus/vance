package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.auth.ProjectBinding;
import de.mhus.vance.foot.config.FootConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginSlashCommandTest {

    private FootConfig config() {
        FootConfig config = new FootConfig();
        config.getBrain().setHttpBase("http://localhost:8080");
        config.getBrain().setWsBase("ws://localhost:8080");
        config.getAuth().setTenant("acme");
        config.getAuth().setUsername("wile.coyote");
        return config;
    }

    private ProjectBinding binding(String http, String ws, String tenant, String user, String project) {
        ProjectBinding b = new ProjectBinding();
        ProjectBinding.Brain brain = new ProjectBinding.Brain();
        brain.setHttpBase(http);
        brain.setWsBase(ws);
        b.setBrain(brain);
        b.setTenant(tenant);
        b.setUsername(user);
        b.setProject(project);
        return b;
    }

    @Test
    void computeDefaults_noBinding_leavesUsernameNullForPrompt() {
        LoginSlashCommand.Defaults d = LoginSlashCommand.computeDefaults(
                List.of(), null, config());

        assertThat(d.hadBinding()).isFalse();
        assertThat(d.username()).isNull(); // config username is NOT a silent default
        assertThat(d.httpBase()).isEqualTo("http://localhost:8080");
        assertThat(d.tenant()).isEqualTo("acme");
        assertThat(d.password()).isNull();
        assertThat(d.project()).isNull();
    }

    @Test
    void computeDefaults_withBinding_usesBindingValues() {
        ProjectBinding b = binding("https://b.example.com", "wss://b.example.com",
                "globex", "hank", "proj-1");

        LoginSlashCommand.Defaults d = LoginSlashCommand.computeDefaults(
                List.of(), b, config());

        assertThat(d.hadBinding()).isTrue();
        assertThat(d.httpBase()).isEqualTo("https://b.example.com");
        assertThat(d.wsBase()).isEqualTo("wss://b.example.com");
        assertThat(d.tenant()).isEqualTo("globex");
        assertThat(d.username()).isEqualTo("hank");
        assertThat(d.project()).isEqualTo("proj-1");
    }

    @Test
    void computeDefaults_argsOverrideBinding() {
        ProjectBinding b = binding("https://b.example.com", "wss://b.example.com",
                "globex", "hank", "proj-1");

        LoginSlashCommand.Defaults d = LoginSlashCommand.computeDefaults(
                List.of("alice", "proj-2", "secret"), b, config());

        assertThat(d.username()).isEqualTo("alice");
        assertThat(d.project()).isEqualTo("proj-2");
        assertThat(d.password()).isEqualTo("secret");
    }

    @Test
    void computeDefaults_oneArg_isUsernameOnly() {
        LoginSlashCommand.Defaults d = LoginSlashCommand.computeDefaults(
                List.of("alice"), null, config());

        assertThat(d.username()).isEqualTo("alice");
        assertThat(d.project()).isNull();
        assertThat(d.password()).isNull();
    }

    @Test
    void deriveWsBase_mapsSchemes() {
        assertThat(LoginSlashCommand.deriveWsBase("https://b.example.com")).isEqualTo("wss://b.example.com");
        assertThat(LoginSlashCommand.deriveWsBase("http://localhost:8080")).isEqualTo("ws://localhost:8080");
        assertThat(LoginSlashCommand.deriveWsBase("wss://already")).isEqualTo("wss://already");
    }
}
