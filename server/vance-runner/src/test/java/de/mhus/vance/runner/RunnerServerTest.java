package de.mhus.vance.runner;

import static org.assertj.core.api.Assertions.assertThat;

import de.mhus.vance.age.AgeKeys;
import de.mhus.vance.runner.protocol.RunnerEnvelope;
import de.mhus.vance.runner.protocol.RunnerEnvelope.OpenedBytes;
import de.mhus.vance.runner.protocol.RunnerEnvelope.SealedRequest;
import de.mhus.vance.runner.protocol.RunnerResponse;
import de.mhus.vance.runner.protocol.RunnerTokenClaims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runner over real HTTP, spoken to the way the brain will: envelopes and ES256 tokens. */
class RunnerServerTest {

    private static final String RUNNER_ID = "runner-acme";
    private static final String TENANT = "acme";

    @TempDir
    Path tmp;

    private KeyPair tenantKey;
    private RunnerIdentity identity;
    private RunnerServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        tenantKey = generator.generateKeyPair();
        identity = RunnerIdentity.loadOrCreate(tmp.resolve("runner.identity"));
        start(256 * 1024);
    }

    private void start(long maxRequestBytes) {
        if (server != null) {
            server.close();
        }
        RunnerConfig config = new RunnerConfig(
                RUNNER_ID,
                TENANT,
                0,
                tmp.resolve("data"),
                tmp.resolve("runner.identity"),
                tmp.resolve("keys.pem"),
                maxRequestBytes);
        server = new RunnerServer(
                config,
                identity,
                List.of(tenantKey.getPublic()),
                List.of(BasicOperations.ping(config), BasicOperations.info(config, List.of("test-capability"))),
                Clock.systemUTC());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ───────────────────────── helpers ─────────────────────────

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private String token(String jti, String tenant, String runner, long lifetimeSeconds) {
        JwtBuilder builder = Jwts.builder()
                .subject("brain")
                .claim(RunnerTokenClaims.CLAIM_TENANT_ID, tenant)
                .claim(RunnerTokenClaims.CLAIM_TYPE, RunnerTokenClaims.TYPE)
                .claim(RunnerTokenClaims.CLAIM_PROJECT_ID, "kunde-x")
                .id(jti)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plusSeconds(lifetimeSeconds)));
        builder.audience().add(RunnerTokenClaims.audience(runner));
        return builder.signWith(tenantKey.getPrivate()).compact();
    }

    private String freshToken(String jti) {
        return token(jti, TENANT, RUNNER_ID, 60);
    }

    private HttpResponse<byte[]> post(byte[] body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(uri("/v1/call"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** One call the way the brain makes it: seal, POST, open with the reply handle. */
    private OpenedBytes<RunnerResponse> call(String token, String jti, String op) throws Exception {
        SealedRequest sealed = RunnerEnvelope.sealRequest(identity.recipient(), token, jti, op, null, new byte[0]);
        HttpResponse<byte[]> response = post(sealed.bytes());
        assertThat(response.statusCode()).isEqualTo(200);
        return sealed.reply().openResponse(response.body());
    }

    // ───────────────────────── the happy path ─────────────────────────

    @Test
    void ping_roundTripsThroughEnvelopeAndToken() throws Exception {
        OpenedBytes<RunnerResponse> reply = call(freshToken("p-1"), "p-1", "ping");

        assertThat(reply.header().status()).isEqualTo(RunnerResponse.Status.OK);
        assertThat(reply.header().result().get("pong").asBoolean()).isTrue();
        assertThat(reply.header().result().get("runnerId").asString()).isEqualTo(RUNNER_ID);
        assertThat(reply.body()).isEmpty();
    }

    @Test
    void info_describesTheRunner() throws Exception {
        OpenedBytes<RunnerResponse> reply = call(freshToken("i-1"), "i-1", "info");

        var info = reply.header().result();
        assertThat(info.get("runnerId").asString()).isEqualTo(RUNNER_ID);
        assertThat(info.get("tenant").asString()).isEqualTo(TENANT);
        assertThat(info.get("protocol").asInt()).isEqualTo(RunnerEnvelope.VERSION);
        assertThat(info.get("capabilities").get(0).asString()).isEqualTo("test-capability");
        assertThat(info.get("data").get("totalBytes").asLong()).isPositive();
    }

    @Test
    void health_isABareOk_andOnlyForGet() throws Exception {
        HttpResponse<String> ok =
                http.send(HttpRequest.newBuilder(uri("/v1/health")).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> post = http.send(
                HttpRequest.newBuilder(uri("/v1/health"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).isEqualTo("ok");
        assertThat(post.statusCode()).isEqualTo(405);
    }

    // ───────────────────────── answers inside the envelope ─────────────────────────

    @Test
    void aRefusedToken_isAnsweredEncrypted_namingTheCall() throws Exception {
        String wrongRunner = token("r-1", TENANT, "runner-other", 60);
        String expired = token("r-2", TENANT, RUNNER_ID, -120);

        for (String[] tokenAndJti : new String[][] {{wrongRunner, "r-1"}, {expired, "r-2"}}) {
            OpenedBytes<RunnerResponse> reply = call(tokenAndJti[0], tokenAndJti[1], "ping");

            assertThat(reply.header().status()).isEqualTo(RunnerResponse.Status.ERROR);
            assertThat(reply.header().error().code()).isEqualTo("token-refused");
        }
    }

    @Test
    void aTokenWorksOnce() throws Exception {
        String token = freshToken("once-1");

        assertThat(call(token, "once-1", "ping").header().status()).isEqualTo(RunnerResponse.Status.OK);
        OpenedBytes<RunnerResponse> second = call(token, "once-1", "ping");

        assertThat(second.header().error().code()).isEqualTo("token-refused");
        assertThat(second.header().error().message()).contains("already used");
    }

    @Test
    void anotherTenantsToken_isRefused_evenWithAValidSignature() throws Exception {
        OpenedBytes<RunnerResponse> reply = call(token("t-1", "other-tenant", RUNNER_ID, 60), "t-1", "ping");

        assertThat(reply.header().error().code()).isEqualTo("wrong-tenant");
    }

    @Test
    void anUnknownOperation_isAnError_notACrash() throws Exception {
        OpenedBytes<RunnerResponse> reply = call(freshToken("u-1"), "u-1", "rm.rf");

        assertThat(reply.header().error().code()).isEqualTo("unknown-op");
        assertThat(reply.header().error().message()).contains("rm.rf");
    }

    @Test
    void anOperationThatThrows_isReportedWithoutLeakingDetails() throws Exception {
        RunnerConfig config = new RunnerConfig(
                RUNNER_ID,
                TENANT,
                0,
                tmp.resolve("data"),
                tmp.resolve("runner.identity"),
                tmp.resolve("keys.pem"),
                1024 * 1024);
        server.close();
        server = new RunnerServer(
                config,
                identity,
                List.of(tenantKey.getPublic()),
                List.of(new RunnerOperation() {
                    @Override
                    public String name() {
                        return "boom";
                    }

                    @Override
                    public OperationResult execute(Call call) {
                        throw new IllegalStateException("secret internals /etc/passwd");
                    }
                }),
                Clock.systemUTC());
        server.start();

        OpenedBytes<RunnerResponse> reply = call(freshToken("b-1"), "b-1", "boom");

        assertThat(reply.header().error().code()).isEqualTo("internal");
        assertThat(reply.header().error().message()).doesNotContain("passwd");
    }

    // ───────────────────────── before there is a key to answer to ─────────────────────────

    @Test
    void whatIsNotAnEnvelopeForThisRunner_getsABareBadRequest() throws Exception {
        byte[] forStranger = RunnerEnvelope.sealRequest(
                        AgeKeys.generateKeyPair().recipient(), freshToken("s-1"), "s-1", "ping", null, new byte[0])
                .bytes();

        for (byte[] body : new byte[][] {"GET / HTTP/1.1".getBytes(StandardCharsets.UTF_8), new byte[0], forStranger}) {
            HttpResponse<byte[]> response = post(body);

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).isEmpty();
        }
    }

    @Test
    void aTokenWithoutACallId_cannotBeAnswered() throws Exception {
        String noJti =
                Jwts.builder().subject("x").signWith(tenantKey.getPrivate()).compact();
        SealedRequest sealed =
                RunnerEnvelope.sealRequest(identity.recipient(), noJti, "irrelevant", "ping", null, new byte[0]);

        assertThat(post(sealed.bytes()).statusCode()).isEqualTo(400);
    }

    @Test
    void getOnTheCallRoute_isNotAllowed() throws Exception {
        HttpResponse<String> response =
                http.send(HttpRequest.newBuilder(uri("/v1/call")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(405);
    }

    @Test
    void anOversizedBody_isRefusedBeforeItIsRead() throws Exception {
        start(4 * 1024);
        SealedRequest sealed = RunnerEnvelope.sealRequest(
                identity.recipient(), freshToken("big-1"), "big-1", "ping", null, new byte[64 * 1024]);

        HttpResponse<byte[]> response = post(sealed.bytes());

        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void aTamperedBodyThatTheOperationIgnores_isStillCaught() throws Exception {
        byte[] body = new byte[100_000];
        SealedRequest sealed =
                RunnerEnvelope.sealRequest(identity.recipient(), freshToken("tam-1"), "tam-1", "ping", null, body);
        byte[] tampered = sealed.bytes().clone();
        tampered[tampered.length - 20] ^= 0x01;

        HttpResponse<byte[]> response = post(tampered);
        assertThat(response.statusCode()).isEqualTo(200);
        OpenedBytes<RunnerResponse> reply = sealed.reply().openResponse(response.body());

        assertThat(reply.header().error().code()).isEqualTo("corrupt-body");
    }

    // ───────────────────────── configuration and keys ─────────────────────────

    @Test
    void identity_isCreatedOnceWithOwnerOnlyPermissions_andReloaded() throws IOException {
        Path file = tmp.resolve("new/id/runner.identity");

        RunnerIdentity first = RunnerIdentity.loadOrCreate(file);
        RunnerIdentity again = RunnerIdentity.loadOrCreate(file);

        assertThat(again).isEqualTo(first);
        assertThat(first.recipient()).startsWith("age1");
        assertThat(Files.getPosixFilePermissions(file).toString()).isEqualTo("[OWNER_READ, OWNER_WRITE]");
        assertThat(first.toString()).doesNotContain("AGE-SECRET-KEY");
    }

    @Test
    void config_readsTheEnvironment_andNamesWhatIsMissing() {
        RunnerConfig config = RunnerConfig.fromEnvironment(java.util.Map.of(
                "VANCE_RUNNER_ID",
                "r1",
                "VANCE_RUNNER_TENANT",
                "acme",
                "VANCE_RUNNER_TENANT_KEYS",
                "/keys/tenant.pem"));

        assertThat(config.port()).isEqualTo(RunnerConfig.DEFAULT_PORT);
        assertThat(config.identityFile()).isEqualTo(Path.of("./data/runner.identity"));
        assertThat(config.maxRequestBytes()).isEqualTo(RunnerConfig.DEFAULT_MAX_REQUEST_BYTES);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RunnerConfig.fromEnvironment(java.util.Map.of("VANCE_RUNNER_ID", "r1")))
                .hasMessage("VANCE_RUNNER_TENANT is required");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RunnerConfig.fromEnvironment(java.util.Map.of(
                        "VANCE_RUNNER_ID",
                        "r1",
                        "VANCE_RUNNER_TENANT",
                        "a",
                        "VANCE_RUNNER_TENANT_KEYS",
                        "k",
                        "VANCE_RUNNER_PORT",
                        "abc")))
                .hasMessageContaining("VANCE_RUNNER_PORT is not a number");
    }

    @SuppressWarnings("unused")
    private static String newJti() {
        return UUID.randomUUID().toString();
    }
}
