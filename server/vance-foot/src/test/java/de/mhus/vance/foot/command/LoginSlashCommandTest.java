package de.mhus.vance.foot.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.access.AccessTokenResponse;
import de.mhus.vance.foot.auth.FootAuthService;
import de.mhus.vance.foot.auth.GitignoreGuard;
import de.mhus.vance.foot.auth.LoginRequest;
import de.mhus.vance.foot.auth.LoginResult;
import de.mhus.vance.foot.auth.ProjectBinding;
import de.mhus.vance.foot.auth.ProjectBindingStore;
import de.mhus.vance.foot.auth.VancePaths;
import de.mhus.vance.foot.config.FootConfig;
import de.mhus.vance.foot.connection.ConnectionService;
import de.mhus.vance.foot.ui.ChatTerminal;
import de.mhus.vance.foot.ui.PendingLinePrompt;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    /**
     * Regression: when a stored binding exists, {@code /login} must STILL
     * prompt for every field, offering the binding values as defaults.
     * Previously a present binding caused brain URL, tenant and username to
     * be taken silently, so only the password prompt appeared — making it
     * impossible to re-login as a different user/tenant without editing
     * project.yaml.
     */
    @Test
    void runLogin_withBinding_promptsAllFieldsWithBindingDefaults() throws Exception {
        ProjectBinding binding = binding("https://b.example.com", "wss://b.example.com",
                "globex", "hank", "proj-1");
        FootConfig config = config();

        ProjectBindingStore bindingStore = mock(ProjectBindingStore.class);
        VancePaths paths = mock(VancePaths.class);
        PendingLinePrompt prompt = mock(PendingLinePrompt.class);
        FootAuthService auth = mock(FootAuthService.class);
        GitignoreGuard gitignore = mock(GitignoreGuard.class);
        ConnectionService connection = mock(ConnectionService.class);
        ChatTerminal terminal = mock(ChatTerminal.class);

        Path vanceDir = Path.of("/tmp/.vancetope");
        when(paths.loginTargetDir()).thenReturn(vanceDir);
        when(bindingStore.load(vanceDir)).thenReturn(Optional.of(binding));
        when(gitignore.ensureAccessIgnored(any())).thenReturn(
                new GitignoreGuard.Result(GitignoreGuard.Kind.NO_GIT, null, null));
        when(auth.login(any())).thenReturn(new LoginResult(
                vanceDir,
                AccessTokenResponse.builder().token("tok").expiresAtTimestamp(1L).build(),
                binding));
        when(connection.isOpen()).thenReturn(false);

        // Accept the default (empty answer) for username, brain URL, tenant,
        // project; enter an explicit password.
        when(prompt.ask(anyString(), anyBoolean(), anyLong()))
                .thenReturn("", "", "", "", "pw");

        LoginSlashCommand cmd = new LoginSlashCommand(
                auth, bindingStore, paths, prompt, gitignore, connection, terminal, config);
        cmd.runLogin(List.of());

        ArgumentCaptor<LoginRequest> captor = ArgumentCaptor.forClass(LoginRequest.class);
        verify(auth).login(captor.capture());
        LoginRequest sent = captor.getValue();
        // Binding values were offered as defaults and accepted via empty input.
        assertThat(sent.username()).isEqualTo("hank");
        assertThat(sent.httpBase()).isEqualTo("https://b.example.com");
        assertThat(sent.wsBase()).isEqualTo("wss://b.example.com");
        assertThat(sent.tenant()).isEqualTo("globex");
        assertThat(sent.project()).isEqualTo("proj-1");
        assertThat(sent.password()).isEqualTo("pw");
    }

    /**
     * When a binding exists but the user types different values at the
     * prompts, the typed values win over the binding defaults.
     */
    @Test
    void runLogin_withBinding_typedValuesOverrideBindingDefaults() throws Exception {
        ProjectBinding binding = binding("https://b.example.com", "wss://b.example.com",
                "globex", "hank", "proj-1");
        FootConfig config = config();

        ProjectBindingStore bindingStore = mock(ProjectBindingStore.class);
        VancePaths paths = mock(VancePaths.class);
        PendingLinePrompt prompt = mock(PendingLinePrompt.class);
        FootAuthService auth = mock(FootAuthService.class);
        GitignoreGuard gitignore = mock(GitignoreGuard.class);
        ConnectionService connection = mock(ConnectionService.class);
        ChatTerminal terminal = mock(ChatTerminal.class);

        Path vanceDir = Path.of("/tmp/.vancetope");
        when(paths.loginTargetDir()).thenReturn(vanceDir);
        when(bindingStore.load(vanceDir)).thenReturn(Optional.of(binding));
        when(gitignore.ensureAccessIgnored(any())).thenReturn(
                new GitignoreGuard.Result(GitignoreGuard.Kind.NO_GIT, null, null));
        when(auth.login(any())).thenReturn(new LoginResult(
                vanceDir,
                AccessTokenResponse.builder().token("tok").expiresAtTimestamp(1L).build(),
                binding));
        when(connection.isOpen()).thenReturn(false);

        // Override username, brain URL, tenant; blank project (drop binding);
        // explicit password.
        when(prompt.ask(anyString(), anyBoolean(), anyLong()))
                .thenReturn("alice", "https://other.example.com", "acme", "", "secret");

        LoginSlashCommand cmd = new LoginSlashCommand(
                auth, bindingStore, paths, prompt, gitignore, connection, terminal, config);
        cmd.runLogin(List.of());

        ArgumentCaptor<LoginRequest> captor = ArgumentCaptor.forClass(LoginRequest.class);
        verify(auth).login(captor.capture());
        LoginRequest sent = captor.getValue();
        assertThat(sent.username()).isEqualTo("alice");
        assertThat(sent.httpBase()).isEqualTo("https://other.example.com");
        assertThat(sent.wsBase()).isEqualTo("wss://other.example.com");
        assertThat(sent.tenant()).isEqualTo("acme");
        // Blank input on an optional field with a default — the default is
        // returned, not null (optionalField returns def on empty).
        assertThat(sent.project()).isEqualTo("proj-1");
        assertThat(sent.password()).isEqualTo("secret");
    }
}
