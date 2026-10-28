package de.mhus.vance.shared.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.runner.RunnerConfig;
import de.mhus.vance.runner.RunnerIdentity;
import de.mhus.vance.runner.RunnerMain;
import de.mhus.vance.runner.RunnerServer;
import de.mhus.vance.runner.RunnerWorkspace;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The admin shell's project delete/rename reaching a real runner. */
class RunnerProjectDataHandlerTest {

    private static final String TENANT = "acme";

    @TempDir
    Path tmp;

    private RunnerWorkspace workspace;
    private RunnerServer server;
    private TenantService tenants;
    private RunnerClient client;
    private RunnerBindingService bindings;
    private RunnerProjectDataHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair tenantKey = generator.generateKeyPair();
        KeyService keys = mock(KeyService.class);
        when(keys.getLatestPrivateKey(TENANT, KeyPurpose.JWT_SIGNING)).thenReturn(Optional.of(tenantKey.getPrivate()));
        RunnerIdentity identity = RunnerIdentity.loadOrCreate(tmp.resolve("runner.identity"));
        RunnerConfig config = new RunnerConfig(
                "runner-acme",
                TENANT,
                0,
                tmp.resolve("data"),
                tmp.resolve("runner.identity"),
                tmp.resolve("k.pem"),
                16L << 20);
        workspace = new RunnerWorkspace(tmp.resolve("data"), null);
        server = new RunnerServer(
                config,
                identity,
                List.of(tenantKey.getPublic()),
                RunnerMain.operations(config, workspace),
                Clock.systemUTC());
        server.start();

        tenants = mock(TenantService.class);
        ProjectService projects = mock(ProjectService.class);
        when(projects.findByTenantAndName(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.empty());
        register("http://localhost:" + server.port(), identity.recipient());
        bindings = new RunnerBindingService(tenants, projects);
        client = new RunnerClient(bindings, new JwtService(keys));
        handler = new RunnerProjectDataHandler(client, bindings);
    }

    @AfterEach
    void tearDown() {
        server.close();
        workspace.close();
    }

    private void register(String url, String recipient) {
        RunnerBinding binding = RunnerBinding.builder()
                .runnerId("runner-acme")
                .url(url)
                .recipient(recipient)
                .build();
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(
                        TenantDocument.builder().name(TENANT).runner(binding).build()));
    }

    private String rootDir(String project) {
        return client.call(
                        TENANT,
                        project,
                        "t",
                        null,
                        "root.create",
                        Map.of("type", "temp", "creatorProcessId", "p"),
                        new byte[0])
                .header()
                .result()
                .get("dirName")
                .asString();
    }

    @Test
    void theHandlerClaimsNoMongoCollection() {
        assertThat(handler.id()).isEqualTo("runner");
        assertThat(handler.collections()).isEmpty();
        assertThat(handler.order()).isEqualTo(2310);
    }

    @Test
    void countsAndDeletesWhatTheProjectKeepsOnTheRunner() {
        rootDir("p1");
        rootDir("p1");
        rootDir("other");

        assertThat(handler.count(TENANT, "p1")).isEqualTo(2);
        assertThat(handler.deleteNote(TENANT, "p1")).contains("RootDirs").contains("runner");

        assertThat(handler.delete(TENANT, "p1")).isEqualTo(2);
        assertThat(handler.count(TENANT, "p1")).isZero();
        assertThat(handler.count(TENANT, "other")).isEqualTo(1);
        // Repeating a delete that already ran is not an error.
        assertThat(handler.delete(TENANT, "p1")).isZero();
    }

    @Test
    void renameMovesTheProjectsDataToTheNewName() {
        String dir = rootDir("old-name");

        assertThat(handler.renameBlocker(TENANT, "old-name", "new-name")).isNull();
        handler.rename(TENANT, "old-name", "new-name");

        assertThat(handler.count(TENANT, "old-name")).isZero();
        assertThat(handler.count(TENANT, "new-name")).isEqualTo(1);
        var read = client.call(TENANT, "new-name", "t", null, "root.list", null, new byte[0]);
        assertThat(read.header().result().get("rootDirs").get(0).get("dirName").asString())
                .isEqualTo(dir);
    }

    @Test
    void renameIsBlockedByARunningJobOrByDataUnderTheNewName() {
        String dir = rootDir("busy");
        client.call(
                TENANT, "busy", "t", null, "exec.start", Map.of("dirName", dir, "command", "sleep 30"), new byte[0]);
        rootDir("taken");

        assertThat(handler.renameBlocker(TENANT, "busy", "fresh")).contains("1 job");
        assertThat(handler.renameBlocker(TENANT, "calm", "taken")).contains("already holds RootDirs");
        assertThat(handler.renameBlocker(TENANT, "calm", "free")).isNull();
        handler.delete(TENANT, "busy");
    }

    @Test
    void aProjectWithoutARunnerHasNothingToAsk() {
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(TenantDocument.builder().name(TENANT).build()));

        assertThat(handler.count(TENANT, "p1")).isZero();
        assertThat(handler.delete(TENANT, "p1")).isZero();
        assertThat(handler.rename(TENANT, "p1", "p2")).isZero();
        assertThat(handler.renameBlocker(TENANT, "p1", "p2")).isNull();
        assertThat(handler.deleteNote(TENANT, "p1")).isNull();
    }

    @Test
    void anUnreachableRunnerIsAnErrorNotNothingToDelete() {
        register(
                "http://localhost:1",
                de.mhus.vance.age.AgeKeys.generateKeyPair().recipient());

        assertThatThrownBy(() -> handler.delete(TENANT, "p1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not answer")
                .hasMessageContaining("untouched");
        assertThatThrownBy(() -> handler.count(TENANT, "p1")).isInstanceOf(IllegalStateException.class);
    }
}
