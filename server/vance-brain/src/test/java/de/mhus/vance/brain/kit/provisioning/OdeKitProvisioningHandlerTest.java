package de.mhus.vance.brain.kit.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The desired-list as it comes off the wire, against a stub ode host.
 *
 * <p>A real socket rather than a mocked client: what matters is how a
 * foreign answer is read — which rows survive, which are skipped, and
 * which failures are told apart — and mocking our own client would only
 * assert that we called ourselves.
 */
class OdeKitProvisioningHandlerTest {

    private HttpServer server;
    private OdeKitProvisioningHandler handler;

    private final List<String> authHeaders = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private String body = """
            {"kits":[{"id":"acme-crm","version":"1.4.0","revision":"abc123",
                      "description":"CRM tools"}]}""";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(OdeKitProvisioningHandler.CAPABILITIES_PATH, this::handle);
        server.start();
        handler = new OdeKitProvisioningHandler(new ObjectMapper());
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private KitProvisioningContext context(KitProvisioningAuthority authority, String token) {
        return new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", baseUrl(), token, authority));
    }

    @Test
    void id_isTheMechanismName() {
        assertThat(handler.id()).isEqualTo("ode");
    }

    @Test
    void discover_mapsDeclaredKitsToTheDesiredList() {
        List<DesiredKit> desired = handler.discover(
                context(KitProvisioningAuthority.UPDATE, null));

        assertThat(desired).singleElement().satisfies(kit -> {
            assertThat(kit.sourceUrl()).isEqualTo(baseUrl());
            assertThat(kit.path()).isEqualTo("acme-crm");
            assertThat(kit.revision()).isEqualTo("abc123");
            assertThat(kit.authority()).isEqualTo(KitProvisioningAuthority.UPDATE);
        });
    }

    @Test
    void discover_withToken_sendsBearer() {
        handler.discover(context(KitProvisioningAuthority.NOTIFY, "s3cr3t"));

        assertThat(authHeaders.getFirst()).isEqualTo("Bearer s3cr3t");
    }

    @Test
    void discover_withoutToken_sendsNoAuthorizationHeader() {
        handler.discover(context(KitProvisioningAuthority.NOTIFY, null));

        assertThat(authHeaders.getFirst()).isNull();
    }

    @Test
    void discover_emptyOffer_isAnAnswerNotAFailure() {
        body = """
                {"kits":[]}""";

        // "Nothing for this project" has to stay distinguishable from
        // "this host is broken" — a caller backs off from the second.
        assertThat(handler.discover(context(KitProvisioningAuthority.NOTIFY, null))).isEmpty();
    }

    @Test
    void discover_rowWithoutId_isSkippedNotFatal() {
        body = """
                {"kits":[{"version":"1"},{"id":"acme-crm","revision":"abc123"}]}""";

        // One unusable row must not cost the project its other kits.
        assertThat(handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .extracting(DesiredKit::path)
                .containsExactly("acme-crm");
    }

    @Test
    void discover_rowWithoutRevision_isKeptWithoutChangeDetection() {
        body = """
                {"kits":[{"id":"acme-crm"}]}""";

        // Installable, just not cheaply checkable. Dropping it would leave a
        // project without the kit and no line saying why.
        assertThat(handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .singleElement()
                .satisfies(kit -> assertThat(kit.revision()).isNull());
    }

    @Test
    void discover_missingKitsArray_isAFailure() {
        body = "{}";

        // Distinguished from an empty array: a missing field is a contract the
        // host is not keeping.
        assertThatThrownBy(() -> handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("without a 'kits' array");
    }

    @Test
    void discover_nonJsonAnswer_isAFailure() {
        body = "<html>nope</html>";

        assertThatThrownBy(() -> handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not json");
    }

    @Test
    void discover_errorStatus_isAFailure() {
        status.set(503);

        assertThatThrownBy(() -> handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("answered HTTP 503");
    }

    @Test
    void discover_unusableUrl_failsWithoutCalling() {
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", "javascript:alert(1)", null,
                        KitProvisioningAuthority.NOTIFY));

        assertThatThrownBy(() -> handler.discover(ctx))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not usable as an endpoint");
        assertThat(authHeaders).isEmpty();
    }

    @Test
    void discover_trailingSlashInUrl_stillReachesTheEndpoint() {
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", baseUrl() + "/", null,
                        KitProvisioningAuthority.NOTIFY));

        assertThat(handler.discover(ctx)).hasSize(1);
    }

    @Test
    void entry_toString_doesNotPrintTheToken() {
        String printed = new KitProvisioningEntry("ode", "https://host", "s3cr3t",
                KitProvisioningAuthority.MANAGE).toString();

        // A record's generated toString would print it into whatever log first
        // mentions the entry.
        assertThat(printed).doesNotContain("s3cr3t").contains("token=set");
    }
}
