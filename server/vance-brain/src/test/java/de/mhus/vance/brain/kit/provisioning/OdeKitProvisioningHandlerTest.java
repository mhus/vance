package de.mhus.vance.brain.kit.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.mhus.vance.api.kit.KitProvisioningAuthority;
import de.mhus.vance.api.kit.KitSourceDto;
import de.mhus.vance.api.kit.KitSourceType;
import de.mhus.vance.brain.kit.KitSourceRegistry;
import de.mhus.vance.shared.kit.KitException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private KitSourceRegistry sources;

    private final List<String> authHeaders = new ArrayList<>();
    /** Query strings the stub saw, so it can be asserted that none carried params. */
    private final List<String> queries = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private String body = """
            {"kits":[{"id":"acme-crm","version":"1.4.0","revision":"abc123",
                      "description":"CRM tools"}]}""";

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(OdeKitProvisioningHandler.CAPABILITIES_PATH, this::handle);
        server.start();
        sources = mock(KitSourceRegistry.class);
        when(sources.resolve(any(), any())).thenReturn(
                KitSourceDto.builder().id("acme").type(KitSourceType.ODE).build());
        handler = new OdeKitProvisioningHandler(new ObjectMapper(), sources);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        queries.add(exchange.getRequestURI().getQuery());
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
                new KitProvisioningEntry("ode", baseUrl(), token, authority, Map.of()));
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
    void discover_sourceNotConfiguredAsOde_saysWhichLineIsMissing() {
        // Without this the run gets further than it should: asking the host works,
        // and then the fetch resolves the same url, guesses GIT and hands an http
        // endpoint to JGit. What the operator saw was a clone stacktrace.
        when(sources.resolve(any(), any())).thenReturn(
                KitSourceDto.builder().id("guessed").type(KitSourceType.GIT).build());

        assertThatThrownBy(() -> handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("type: ode")
                .hasMessageContaining("kit-sources.yaml");
        assertThat(authHeaders).isEmpty();
    }

    @Test
    void discover_unresolvableSource_doesNotBlockTheRun() {
        // Resolution is somebody else's logic; failing the entry over a
        // diagnostic lookup would be the wrong trade.
        when(sources.resolve(any(), any())).thenThrow(new IllegalStateException("nope"));

        assertThat(handler.discover(context(KitProvisioningAuthority.NOTIFY, null))).hasSize(1);
    }

    @Test
    void discover_notFound_namesTheLikelyCause() {
        status.set(404);

        assertThatThrownBy(() -> handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("serves no kit endpoint")
                .hasMessageContaining("switched off");
    }

    @Test
    void discover_rejectedCredential_pointsAtTheToken() {
        status.set(403);

        assertThatThrownBy(() -> handler.discover(context(KitProvisioningAuthority.NOTIFY, null)))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("rejected the credential");
    }

    @Test
    void discover_unusableUrl_failsWithoutCalling() {
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", "javascript:alert(1)", null,
                        KitProvisioningAuthority.NOTIFY, Map.of()));

        assertThatThrownBy(() -> handler.discover(ctx))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not usable as an endpoint");
        assertThat(authHeaders).isEmpty();
    }

    @Test
    void discover_mailtoUrl_failsAsAKitExceptionNotAn500() {
        // SafeLink's allow-list includes mailto: — it answers "may a human be
        // shown this". As a request target it would reach
        // HttpRequest.newBuilder and throw an IllegalArgumentException nobody
        // catches, i.e. a 500 instead of the explained 400.
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", "mailto:ops@example.com", null,
                        KitProvisioningAuthority.NOTIFY, Map.of()));

        assertThatThrownBy(() -> handler.discover(ctx))
                .isInstanceOf(KitException.class)
                .hasMessageContaining("not an http(s) endpoint");
        assertThat(authHeaders).isEmpty();
    }

    @Test
    void discover_trailingSlashInUrl_stillReachesTheEndpoint() {
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", baseUrl() + "/", null,
                        KitProvisioningAuthority.NOTIFY, Map.of()));

        assertThat(handler.discover(ctx)).hasSize(1);
    }

    @Test
    void discover_carriesTheEntryParamsIntoEachDesiredKit() {
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", baseUrl(), null,
                        KitProvisioningAuthority.UPDATE, Map.of("lang", "de")));

        // Needed by the fetch, and load-bearing for the check: the revision
        // comes from a call that never saw these params.
        assertThat(handler.discover(ctx)).singleElement()
                .satisfies(kit -> assertThat(kit.params()).containsEntry("lang", "de"));
    }

    @Test
    void discover_doesNotSendParamsToCapabilities() {
        KitProvisioningContext ctx = new KitProvisioningContext("acme", "sales",
                new KitProvisioningEntry("ode", baseUrl(), null,
                        KitProvisioningAuthority.UPDATE, Map.of("lang", "de")));

        handler.discover(ctx);

        // This call has to stay cacheable and caller-independent — that is
        // what makes the periodic check cheap. Params belong to the build.
        assertThat(queries).containsExactly((String) null);
    }

    @Test
    void entry_toString_doesNotPrintTheToken() {
        String printed = new KitProvisioningEntry("ode", "https://host", "s3cr3t",
                KitProvisioningAuthority.MANAGE, Map.of("lang", "de")).toString();

        // A record's generated toString would print it into whatever log first
        // mentions the entry.
        assertThat(printed).doesNotContain("s3cr3t").contains("token=set");
    }
}
