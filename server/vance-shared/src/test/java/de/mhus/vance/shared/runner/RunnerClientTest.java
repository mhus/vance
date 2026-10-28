package de.mhus.vance.shared.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.runner.RunnerConfig;
import de.mhus.vance.runner.RunnerIdentity;
import de.mhus.vance.runner.RunnerOperation;
import de.mhus.vance.runner.RunnerServer;
import de.mhus.vance.shared.jwt.JwtService;
import de.mhus.vance.shared.keystore.KeyPurpose;
import de.mhus.vance.shared.keystore.KeyService;
import de.mhus.vance.shared.project.ProjectDocument;
import de.mhus.vance.shared.project.ProjectService;
import de.mhus.vance.shared.runner.RunnerClient.RunnerCallException;
import de.mhus.vance.shared.runner.RunnerClient.RunnerCallException.Reason;
import de.mhus.vance.shared.tenant.TenantDocument;
import de.mhus.vance.shared.tenant.TenantService;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The brain's {@link RunnerClient} against a real {@link RunnerServer} over HTTP, with
 * tokens minted by the real {@link JwtService} — the two halves of
 * {@code planning/runner.md} §4 meeting for the first time.
 */
class RunnerClientTest {

    private static final String TENANT = "acme";

    @TempDir
    Path tmp;

    private final AtomicReference<RunnerOperation.Call> lastCall = new AtomicReference<>();
    private RunnerIdentity runnerIdentity;
    private RunnerServer server;
    private TenantService tenants;
    private ProjectService projects;
    private RunnerClient client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair tenantKey = generator.generateKeyPair();
        KeyService keys = mock(KeyService.class);
        when(keys.getLatestPrivateKey(TENANT, KeyPurpose.JWT_SIGNING)).thenReturn(Optional.of(tenantKey.getPrivate()));
        JwtService jwt = new JwtService(keys);

        runnerIdentity = RunnerIdentity.loadOrCreate(tmp.resolve("runner.identity"));
        RunnerConfig config = new RunnerConfig(
                "runner-acme",
                TENANT,
                0,
                tmp.resolve("data"),
                tmp.resolve("runner.identity"),
                tmp.resolve("keys.pem"),
                16L * 1024 * 1024);
        server = new RunnerServer(
                config,
                runnerIdentity,
                List.of(tenantKey.getPublic()),
                List.of(new RunnerOperation() {
                    @Override
                    public String name() {
                        return "echo";
                    }

                    @Override
                    public OperationResult execute(Call call) throws OperationException {
                        lastCall.set(call);
                        try {
                            return OperationResult.of(
                                    Map.of("args", String.valueOf(call.args())),
                                    new java.io.ByteArrayInputStream(call.body().readAllBytes()));
                        } catch (java.io.IOException e) {
                            throw new OperationException("io", e.getMessage(), e);
                        }
                    }
                }),
                Clock.systemUTC());
        server.start();

        tenants = mock(TenantService.class);
        projects = mock(ProjectService.class);
        client = new RunnerClient(new RunnerBindingService(tenants, projects), jwt);
        registerTenantRunner("http://localhost:" + server.port(), runnerIdentity.recipient());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private void registerTenantRunner(String url, String recipient) {
        RunnerBinding binding = RunnerBinding.builder()
                .runnerId("runner-acme")
                .url(url)
                .recipient(recipient)
                .build();
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(
                        TenantDocument.builder().name(TENANT).runner(binding).build()));
        when(projects.findByTenantAndName(TENANT, "kunde-x"))
                .thenReturn(Optional.of(ProjectDocument.builder()
                        .tenantId(TENANT)
                        .name("kunde-x")
                        .build()));
    }

    @Test
    void aCallRoundTrips_withArgsBodyAndTheRealTokenClaims() {
        RunnerClient.Reply reply = client.call(
                TENANT,
                "kunde-x",
                "alice",
                "_python",
                "echo",
                Map.of("path", "a.txt"),
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(reply.ok()).isTrue();
        assertThat(new String(reply.body(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(reply.runner().getRunnerId()).isEqualTo("runner-acme");
        assertThat(reply.header().result().get("args").asString()).contains("a.txt");
        // The runner saw exactly the scope the brain put into the token.
        assertThat(lastCall.get().claims().tenantId()).isEqualTo(TENANT);
        assertThat(lastCall.get().claims().projectId()).isEqualTo("kunde-x");
        assertThat(lastCall.get().claims().rootDir()).isEqualTo("_python");
    }

    @Test
    void aTenantLevelCall_usesThePseudoProject() {
        client.call(TENANT, null, "alice", null, "echo", null, new byte[0]);

        assertThat(lastCall.get().claims().projectId()).isEqualTo(RunnerClient.TENANT_PROJECT);
        assertThat(lastCall.get().claims().rootDir()).isNull();
    }

    @Test
    void aRunnerSideFailure_isAReply_notAnException() {
        RunnerClient.Reply reply = client.call(TENANT, "kunde-x", "alice", null, "no-such-op", null, new byte[0]);

        assertThat(reply.ok()).isFalse();
        assertThat(reply.header().error().code()).isEqualTo("unknown-op");
    }

    @Test
    void everyCallGetsItsOwnToken() {
        client.call(TENANT, "kunde-x", "alice", null, "echo", null, new byte[0]);
        String first = lastCall.get().claims().jti();
        client.call(TENANT, "kunde-x", "alice", null, "echo", null, new byte[0]);

        assertThat(lastCall.get().claims().jti()).isNotEqualTo(first);
    }

    @Test
    void noRegisteredRunner_isNamedAsSuch() {
        when(tenants.findByName(TENANT))
                .thenReturn(Optional.of(TenantDocument.builder().name(TENANT).build()));

        assertThatThrownBy(() -> client.call(TENANT, "kunde-x", "alice", null, "echo", null, new byte[0]))
                .isInstanceOfSatisfying(RunnerCallException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.NO_RUNNER);
                    assertThat(e.getMessage()).contains("acme/kunde-x");
                });
    }

    @Test
    void aRunnerThatIsNotThere_isUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registerTenantRunner("http://localhost:" + closedPort, runnerIdentity.recipient());

        assertThatThrownBy(() -> client.call(TENANT, "kunde-x", "alice", null, "echo", null, new byte[0]))
                .isInstanceOfSatisfying(
                        RunnerCallException.class, e -> assertThat(e.reason()).isEqualTo(Reason.UNREACHABLE));
    }

    @Test
    void theWrongKeyRegistered_isRejectedWithAHint_andNothingReadable() {
        // The address is right but the registered key is somebody else's: the runner
        // cannot open the envelope, and says so without revealing anything.
        registerTenantRunner(
                "http://localhost:" + server.port(), AgeKeys.generateKeyPair().recipient());

        assertThatThrownBy(() -> client.call(TENANT, "kunde-x", "alice", null, "echo", null, new byte[0]))
                .isInstanceOfSatisfying(RunnerCallException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.REJECTED);
                    assertThat(e.getMessage()).contains("HTTP 400").contains("registered key");
                });
    }

    @Test
    void aStoredKeyThatIsNotAnAgeRecipient_isABadRegistration_notAProtocolCrash() {
        registerTenantRunner("http://localhost:" + server.port(), "not-a-key");

        assertThatThrownBy(() -> client.call(TENANT, "kunde-x", "alice", null, "echo", null, new byte[0]))
                .isInstanceOfSatisfying(RunnerCallException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.BAD_REGISTRATION);
                    assertThat(e.getMessage()).contains("runner set");
                });
    }

    @Test
    void aHeaderOverItsCap_isARejectedRequest_notABadRegistration() {
        // 2 MiB of arguments does not fit the 1 MiB header; the stored key is fine.
        Map<String, Object> args = Map.of("blob", "x".repeat(2 * 1024 * 1024));

        assertThatThrownBy(() -> client.call(TENANT, "kunde-x", "alice", null, "echo", args, new byte[0]))
                .isInstanceOfSatisfying(RunnerCallException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.REJECTED);
                    assertThat(e.getMessage()).contains("cannot be sent").contains("Header too large");
                });
    }

    @Test
    void aBodyIsDeliveredWhole_whatEverItsSize() {
        byte[] big = new byte[5 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'z');

        RunnerClient.Reply reply = client.call(TENANT, "kunde-x", "alice", null, "echo", null, big);

        assertThat(reply.ok()).isTrue();
        assertThat(reply.body()).hasSize(big.length);
    }
}
