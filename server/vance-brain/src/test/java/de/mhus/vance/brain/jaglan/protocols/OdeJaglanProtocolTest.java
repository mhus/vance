package de.mhus.vance.brain.jaglan.protocols;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.mhus.vance.api.documents.MountAccess;
import de.mhus.vance.api.mount.MountedStat;
import de.mhus.vance.toolpack.jaglan.JaglanCapabilities;
import de.mhus.vance.toolpack.jaglan.JaglanInstance;
import de.mhus.vance.toolpack.jaglan.JaglanInstanceConfig;
import de.mhus.vance.toolpack.jaglan.JaglanProtocolException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The reading end of the {@code vance-ode-jaglan} contract.
 *
 * <p>Two things are defended here above all: that a 404 and a 500 stay
 * different answers, and that content never becomes a String on the way
 * through.
 */
class OdeJaglanProtocolTest {

    private static final String BASE = "https://library.test/ode/files";

    private RecordingClient client;

    private JaglanInstance instance() {
        return instance("");
    }

    private JaglanInstance instance(String apiKey) {
        client = client == null ? new RecordingClient() : client;
        return new OdeJaglanProtocol(client).instantiate(new JaglanInstanceConfig(
                "library", OdeJaglanProtocol.ID, BASE, "jaglan.mount.library.apiKey",
                () -> apiKey.isEmpty() ? null : apiKey, "acme", "research", Map.of()));
    }

    // ─── instantiation ──────────────────────────────────────────────────

    @Test
    void instantiate_withoutBaseUrl_isRefusedUpFront() {
        assertThatThrownBy(() -> new OdeJaglanProtocol(new RecordingClient())
                .instantiate(new JaglanInstanceConfig(
                        "library", OdeJaglanProtocol.ID, "", "",
                        () -> null, "acme", "research", Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl is required");
    }

    @Test
    void baseUrl_trailingSlashDoesNotDoubleUp() {
        client = new RecordingClient();
        JaglanInstance withSlash = new OdeJaglanProtocol(client).instantiate(
                new JaglanInstanceConfig("library", OdeJaglanProtocol.ID, BASE + "/", "",
                        () -> null, "acme", "research", Map.of()));
        client.jsonBody = "{\"access\":\"READ_ONLY\"}";

        withSlash.capabilities();

        assertThat(client.lastUrl.toString()).isEqualTo(BASE + "/capabilities");
    }

    // ─── capabilities ───────────────────────────────────────────────────

    @Test
    void capabilities_readsAccessSearchAndAnIso8601Ttl() {
        client = new RecordingClient();
        client.jsonBody = """
                {"access":"READ_WRITE","canSearch":true,"itemCount":4200,
                 "metadataTtl":"PT3M","maxBytes":1048576,"displayName":"Book Library"}
                """;

        JaglanCapabilities caps = instance().capabilities();

        assertThat(caps.access()).isEqualTo(MountAccess.RW);
        assertThat(caps.canSearch()).isTrue();
        assertThat(caps.itemCount()).isEqualTo(4200L);
        assertThat(caps.metadataTtl()).isEqualTo(Duration.ofMinutes(3));
        assertThat(caps.maxBytes()).isEqualTo(1_048_576L);
        assertThat(caps.displayName()).isEqualTo("Book Library");
    }

    @Test
    void capabilities_unparseableTtlFallsBackRatherThanBecomingZero() {
        client = new RecordingClient();
        client.jsonBody = "{\"access\":\"READ_ONLY\",\"metadataTtl\":\"soon\"}";

        // Zero would mean "do not cache" — a very different statement from
        // "the source sent something we could not read".
        assertThat(instance().capabilities().metadataTtl())
                .isEqualTo(JaglanCapabilities.DEFAULT_TTL);
    }

    @Test
    void capabilities_unknownAccessValueIsReadOnly() {
        client = new RecordingClient();
        client.jsonBody = "{\"access\":\"SOMETHING_NEW\"}";

        // The pessimistic reading: never assume a write the source did not
        // clearly offer.
        assertThat(instance().capabilities().access()).isEqualTo(MountAccess.RO);
    }

    // ─── stat: gone versus broken ───────────────────────────────────────

    @Test
    void stat_404_isEmptyAndAuthoritative() {
        client = new RecordingClient();
        client.status = 404;

        assertThat(instance().stat("books/dune.pdf")).isEmpty();
    }

    @Test
    void stat_500_isATransientFailureNotAnEmptyAnswer() {
        client = new RecordingClient();
        client.status = 500;

        // The distinction the shell layer depends on: an outage must keep the
        // cached row, and only an answer may delete it.
        assertThatThrownBy(() -> instance().stat("books/dune.pdf"))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isFalse());
    }

    @Test
    void stat_405_isARefusal() {
        client = new RecordingClient();
        client.status = 405;

        assertThatThrownBy(() -> instance().stat("x.pdf"))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isTrue());
    }

    @Test
    void stat_mapsTheEntryFields() {
        client = new RecordingClient();
        client.jsonBody = """
                {"path":"books/dune.pdf","folder":false,"size":5,
                 "mimeType":"application/pdf","etag":"e1","modifiedAtMs":1700000000000}
                """;

        Optional<MountedStat> stat = instance().stat("books/dune.pdf");

        assertThat(stat).isPresent();
        assertThat(stat.get().size()).isEqualTo(5);
        assertThat(stat.get().mimeType()).isEqualTo("application/pdf");
        assertThat(stat.get().etag()).isEqualTo("e1");
        assertThat(stat.get().modifiedAtMs()).isEqualTo(1_700_000_000_000L);
        // Per-entry access is not in this wire contract — the mount declares it
        // once, so a guess here would override the real answer.
        assertThat(stat.get().access()).isEqualTo(MountAccess.UNKNOWN);
    }

    @Test
    void unreadableJson_isTransientNotARefusal() {
        client = new RecordingClient();
        client.jsonBody = "{not json";

        assertThatThrownBy(() -> instance().stat("x.pdf"))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isFalse());
    }

    // ─── list ───────────────────────────────────────────────────────────

    @Test
    void list_skipsAnEntryWithoutAPathRatherThanFailingTheFolder() {
        client = new RecordingClient();
        client.jsonBody = """
                [{"path":"books","folder":true},
                 {"folder":false,"size":3},
                 {"path":"readme.md","folder":false,"size":7}]
                """;

        assertThat(instance().list("")).extracting(MountedStat::path)
                .containsExactly("books", "readme.md");
    }

    @Test
    void list_encodesThePathParameter() {
        client = new RecordingClient();
        client.jsonBody = "[]";

        instance().list("my folder/a&b");

        // A file name with a space or an ampersand is ordinary and would
        // otherwise arrive truncated or as two parameters.
        assertThat(client.lastUrl.toString())
                .isEqualTo(BASE + "/list?path=my+folder%2Fa%26b");
    }

    // ─── content stays a stream ─────────────────────────────────────────

    @Test
    void open_handsOutTheStreamUnread() throws IOException {
        client = new RecordingClient();
        client.streamBody = "spice";

        try (InputStream in = instance().open("books/dune.pdf")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("spice");
        }
        // The whole point of the mount: the JSON path was never involved.
        assertThat(client.streamCalls).isEqualTo(1);
        assertThat(client.jsonCalls).isZero();
    }

    @Test
    void open_404_isAFailureNotAnEmptyStream() {
        client = new RecordingClient();
        client.status = 404;

        // Unlike stat, there is no "empty" to return here — an empty stream
        // would read as an empty file.
        assertThatThrownBy(() -> instance().open("gone.pdf"))
                .isInstanceOf(JaglanProtocolException.class);
    }

    @Test
    void write_streamsTheBodyAndReturnsTheNewEntry() {
        client = new RecordingClient();
        client.jsonBody = "{\"path\":\"notes/new.txt\",\"size\":7,\"etag\":\"e2\"}";

        MountedStat stat = instance().write("notes/new.txt",
                new ByteArrayInputStream("written".getBytes(StandardCharsets.UTF_8)));

        assertThat(stat.size()).isEqualTo(7);
        assertThat(stat.etag()).isEqualTo("e2");
        assertThat(client.putCalls).isEqualTo(1);
    }

    @Test
    void write_405_isARefusalTheCallerShouldStopRetrying() {
        client = new RecordingClient();
        client.status = 405;

        assertThatThrownBy(() -> instance().write("x.txt",
                new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isTrue());
    }

    @Test
    void delete_404_countsAsDone() {
        client = new RecordingClient();
        client.status = 404;

        // The caller wanted it gone, and it is.
        instance().delete("already-gone.txt");

        assertThat(client.deleteCalls).isEqualTo(1);
    }

    @Test
    void delete_500_fails() {
        client = new RecordingClient();
        client.status = 500;

        assertThatThrownBy(() -> instance().delete("x.txt"))
                .isInstanceOf(JaglanProtocolException.class);
    }

    // ─── credential ─────────────────────────────────────────────────────

    @Test
    void apiKey_travelsAsABearerHeader() {
        client = new RecordingClient();
        client.jsonBody = "{\"access\":\"READ_ONLY\"}";

        instance("s3cret").capabilities();

        assertThat(client.lastHeaders).containsEntry("Authorization", "Bearer s3cret");
    }

    @Test
    void noApiKey_sendsNoAuthorizationHeader() {
        client = new RecordingClient();
        client.jsonBody = "{\"access\":\"READ_ONLY\"}";

        instance().capabilities();

        assertThat(client.lastHeaders).doesNotContainKey("Authorization");
    }

    @Test
    void transportException_isTransient() {
        client = new RecordingClient();
        client.throwOnCall = new IOException("connect timeout");

        assertThatThrownBy(() -> instance().stat("x.pdf"))
                .isInstanceOf(JaglanProtocolException.class)
                .satisfies(e -> assertThat(((JaglanProtocolException) e).isRefused()).isFalse());
    }

    // ─── test double ────────────────────────────────────────────────────

    private static class RecordingClient implements JaglanHttpClient {

        int status = 200;
        String jsonBody = "{}";
        String streamBody = "";
        Exception throwOnCall;

        int jsonCalls;
        int streamCalls;
        int putCalls;
        int deleteCalls;
        URI lastUrl;
        Map<String, String> lastHeaders = Map.of();
        final List<URI> urls = new ArrayList<>();

        @Override
        public Response get(URI url, Map<String, String> headers, Duration timeout)
                throws Exception {
            record(url, headers);
            jsonCalls++;
            if (throwOnCall != null) throw throwOnCall;
            return new Response(status, jsonBody);
        }

        @Override
        public StreamResponse getStream(URI url, Map<String, String> headers, Duration timeout)
                throws Exception {
            record(url, headers);
            streamCalls++;
            if (throwOnCall != null) throw throwOnCall;
            InputStream body = status >= 200 && status < 300
                    ? new ByteArrayInputStream(streamBody.getBytes(StandardCharsets.UTF_8))
                    : null;
            return new StreamResponse(status, body, Map.of());
        }

        @Override
        public Response put(URI url, InputStream body, Map<String, String> headers,
                Duration timeout) throws Exception {
            record(url, headers);
            putCalls++;
            if (throwOnCall != null) throw throwOnCall;
            return new Response(status, jsonBody);
        }

        @Override
        public Response delete(URI url, Map<String, String> headers, Duration timeout)
                throws Exception {
            record(url, headers);
            deleteCalls++;
            if (throwOnCall != null) throw throwOnCall;
            return new Response(status, jsonBody);
        }

        private void record(URI url, Map<String, String> headers) {
            lastUrl = url;
            lastHeaders = headers;
            urls.add(url);
        }
    }
}
