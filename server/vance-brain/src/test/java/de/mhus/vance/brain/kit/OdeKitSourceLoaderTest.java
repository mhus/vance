package de.mhus.vance.brain.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.api.kit.KitInheritDto;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.shared.instance.InstanceProperties;
import de.mhus.vance.shared.kit.KitException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Wire behaviour of the ode kit loader against a local stub host.
 *
 * <p>A real socket rather than a mocked {@code HttpClient}: what is worth
 * asserting here is the request the host actually receives — the identity
 * fields and the absence of everything else — and a mock of our own client
 * would only assert that we called ourselves correctly.
 */
class OdeKitSourceLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private OdeKitSourceLoader loader;
    private final InstanceProperties instance = new InstanceProperties();

    /** Bodies the stub received, in order. */
    private final List<String> received = new ArrayList<>();
    /** Authorization headers seen, null entry when the request carried none. */
    private final List<String> authHeaders = new ArrayList<>();

    private final AtomicInteger status = new AtomicInteger(200);
    private byte[] payload = zip(Map.of("kit.yaml",
            "name: acme-crm\nversion: 1.4.0\ndescription: CRM tools\n"));

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(OdeKitSourceLoader.BUILD_PATH, this::handle);
        server.start();
        loader = new OdeKitSourceLoader(instance, MAPPER);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        int code = status.get();
        if (code != 200) {
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private KitSourceDto source() {
        return KitSourceDto.builder()
                .id("acme")
                .type(KitSourceType.ODE)
                .url(baseUrl())
                .build();
    }

    private static KitInheritDto reference() {
        KitInheritDto ref = new KitInheritDto();
        ref.setUrl("http://irrelevant");
        ref.setPath("acme-crm");
        return ref;
    }

    @Test
    void load_deliveredKit_unpacksAndParsesDescriptor(@TempDir Path target) {
        KitRepoLoader.LoadedKit loaded = loader.load(
                reference(), source(),
                new KitAccess("acme", "sales", null, null), target);

        assertThat(loaded.descriptor().getName()).isEqualTo("acme-crm");
        assertThat(loaded.commit()).isEqualTo("ode:1.4.0");
        assertThat(target.resolve("kit.yaml")).exists();
    }

    @Test
    void load_sendsTenantProjectAndAccessUrl(@TempDir Path target) {
        instance.setName("acme-prod");

        loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target);

        JsonNode body = MAPPER.readTree(received.getFirst());
        assertThat(body.get("kit").asString()).isEqualTo("acme-crm");
        assertThat(body.get("instance").asString()).isEqualTo("acme-prod");
        assertThat(body.get("tenant").asString()).isEqualTo("acme");
        assertThat(body.get("project").asString()).isEqualTo("sales");
        assertThat(body.get("accessUrl").asString()).isEqualTo(baseUrl());
    }

    @Test
    void load_unsetInstanceName_sendsNoStandIn(@TempDir Path target) {
        loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target);

        // Null, not "default" and not the pod name: a host reads a missing
        // value as unknown but would log a stand-in as a customer name.
        assertThat(MAPPER.readTree(received.getFirst()).get("instance").isNull()).isTrue();
    }

    @Test
    void load_carriesNoUserIdentity(@TempDir Path target) {
        loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target);

        assertThat(MAPPER.readTree(received.getFirst()).propertyNames())
                .containsExactlyInAnyOrder("kit", "instance", "tenant", "project", "accessUrl");
    }

    @Test
    void load_withToken_sendsBearer(@TempDir Path target) {
        loader.load(reference(), source(),
                new KitAccess("acme", "sales", "s3cr3t", null), target);

        assertThat(authHeaders.getFirst()).isEqualTo("Bearer s3cr3t");
    }

    @Test
    void load_withoutToken_sendsNoAuthorizationHeader(@TempDir Path target) {
        loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target);

        assertThat(authHeaders.getFirst()).isNull();
    }

    @Test
    void load_blankPath_failsBeforeCallingTheHost(@TempDir Path target) {
        KitInheritDto ref = reference();
        ref.setPath("  ");

        assertThatThrownBy(() -> loader.load(ref, source(),
                new KitAccess("acme", "sales", null, null), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("needs a path");
        assertThat(received).isEmpty();
    }

    @Test
    void load_rejectedCredential_saysSo(@TempDir Path target) {
        status.set(403);

        assertThatThrownBy(() -> loader.load(reference(), source(),
                new KitAccess("acme", "sales", "wrong", null), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("rejected the credential");
    }

    @Test
    void load_unknownKit_namesTheKit(@TempDir Path target) {
        status.set(404);

        assertThatThrownBy(() -> loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("does not serve a kit 'acme-crm'");
    }

    @Test
    void load_deliveryWithoutDescriptor_failsWithTheReason(@TempDir Path target) {
        payload = zip(Map.of("tools/crm.yaml", "name: crm\n"));

        assertThatThrownBy(() -> loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("without a kit.yaml");
    }

    @Test
    void load_archiveEscapingTheTarget_isRefused(@TempDir Path target) throws IOException {
        payload = zip(Map.of("../escaped.md", "nope\n"));

        assertThatThrownBy(() -> loader.load(reference(), source(),
                new KitAccess("acme", "sales", null, null), target))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("failed to unpack");
        assertThat(Files.exists(target.getParent().resolve("escaped.md"))).isFalse();
    }

    @Test
    void load_trailingSlashInUrl_doesNotDoubleUp(@TempDir Path target) {
        KitSourceDto withSlash = source();
        withSlash.setUrl(baseUrl() + "/");

        loader.load(reference(), withSlash,
                new KitAccess("acme", "sales", null, null), target);

        assertThat(MAPPER.readTree(received.getFirst()).get("accessUrl").asString())
                .isEqualTo(baseUrl());
    }

    private static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to build the test archive", e);
        }
        return out.toByteArray();
    }
}
