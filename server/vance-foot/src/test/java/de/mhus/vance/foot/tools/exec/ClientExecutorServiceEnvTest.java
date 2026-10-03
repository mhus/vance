package de.mhus.vance.foot.tools.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.foot.permission.ExecIsolation;
import de.mhus.vance.foot.permission.PermissionConfig;
import de.mhus.vance.foot.permission.PermissionConfigLoader;
import de.mhus.vance.foot.permission.PermissionService;
import de.mhus.vance.toolpack.exec.ExecEnvPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The foot half of the exec environment sealing (GitHub issue {@code
 * mhus/vance#62}): an agent-driven {@code client_exec_run} command runs on the
 * user's machine, where the parent process environment holds SSH agent sockets,
 * cloud credentials and tokens. The child may see the non-secret base plus the
 * names the user granted in {@code permissions.yaml} — nothing else.
 */
@DisabledOnOs(OS.WINDOWS)
class ClientExecutorServiceEnvTest {

    /** Variables a POSIX shell adds to its own environment on startup. */
    private static final Set<String> SHELL_NATIVE = Set.of("PWD", "SHLVL", "_", "OLDPWD");

    @Test
    void runJob_subprocessSeesOnlyTheSealedBaseAndUsesTheConfiguredPassThrough() throws Exception {
        PermissionService permissions = mock(PermissionService.class);
        when(permissions.execEnvAllow()).thenReturn(List.of());
        when(permissions.isolation()).thenReturn(ExecIsolation.DISABLED);
        ClientExecutorService service = service(permissions);

        ClientExecJob job = service.submit("env");
        awaitTerminal(job);

        Map<String, String> childEnv = parseEnv(job.readStdout());
        assertThat(childEnv).isNotEmpty();
        for (String key : childEnv.keySet()) {
            assertThat(ExecEnvPolicy.INHERITABLE_NAMES.contains(key) || SHELL_NATIVE.contains(key))
                    .as("unexpected variable leaked into the client subprocess env: " + key)
                    .isTrue();
        }
        // Wiring check: the executor consults the user's pass-through list.
        verify(permissions).execEnvAllow();
    }

    @Test
    void permissionService_readsExecEnvAllowFromConfig() {
        PermissionConfig config = new PermissionConfig();
        PermissionConfig.Exec exec = new PermissionConfig.Exec();
        PermissionConfig.Env env = new PermissionConfig.Env();
        env.setAllow(List.of("SSH_AUTH_SOCK", "GH_TOKEN"));
        exec.setEnv(env);
        config.setExec(exec);

        PermissionConfigLoader loader = mock(PermissionConfigLoader.class);
        when(loader.effectiveConfig()).thenReturn(config);

        assertThat(new PermissionService(loader).execEnvAllow()).containsExactly("SSH_AUTH_SOCK", "GH_TOKEN");
    }

    @Test
    void permissionService_withoutExecBlockHasEmptyPassThrough() {
        PermissionConfigLoader loader = mock(PermissionConfigLoader.class);
        when(loader.effectiveConfig()).thenReturn(new PermissionConfig());

        assertThat(new PermissionService(loader).execEnvAllow()).isEmpty();
    }

    private static ClientExecutorService service(PermissionService permissions) {
        @SuppressWarnings("unchecked")
        ObjectProvider<FootExecEventDispatcher> dispatcher = mock(ObjectProvider.class);
        return new ClientExecutorService(dispatcher, permissions);
    }

    private static void awaitTerminal(ClientExecJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(job.isTerminal()).as("exec job did not finish in time").isTrue();
    }

    private static Map<String, String> parseEnv(String stdout) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : stdout.split("\n")) {
            int idx = line.indexOf('=');
            if (idx > 0) {
                out.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
        return out;
    }
}
