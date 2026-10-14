package de.mhus.vance.brain.oauthserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientMetadataDocumentResolverTest {

    private static final String URL = "https://app.example.com/oauth/client.json";

    /** Resolver with fixed DNS and body — no network. */
    private static final class Stub extends ClientMetadataDocumentResolver {
        private final String ip;
        private final String body;
        final AtomicInteger fetches = new AtomicInteger();

        Stub(String ip, String body) {
            this.ip = ip;
            this.body = body;
        }

        @Override
        InetAddress[] resolveHost(String host) throws UnknownHostException {
            return new InetAddress[] {InetAddress.getByName(ip)};
        }

        @Override
        String fetch(URI uri) {
            fetches.incrementAndGet();
            return body;
        }
    }

    private static String doc(String clientId, String redirect) {
        return """
                {"client_id":"%s","client_name":"Example","redirect_uris":["%s"],
                 "token_endpoint_auth_method":"none"}
                """.formatted(clientId, redirect);
    }

    @Test
    void resolve_validDocument_isCachedAndKindCimd() {
        Stub stub = new Stub("93.184.215.14", doc(URL, "http://127.0.0.1:3000/callback"));

        ResolvedOAuthClient client = stub.resolve(URL);
        stub.resolve(URL);

        assertThat(client.kind()).isEqualTo(OAuthClientKind.CIMD);
        assertThat(client.clientName()).isEqualTo("Example");
        assertThat(client.allowsRedirect("http://127.0.0.1:41000/callback")).isTrue();
        assertThat(stub.fetches).hasValue(1);
    }

    @Test
    void resolve_documentNamingAnotherUrl_isRejected() {
        Stub stub = new Stub("93.184.215.14", doc("https://other.example/c.json", "https://app.example.com/cb"));

        assertThatThrownBy(() -> stub.resolve(URL))
                .isInstanceOf(InvalidClientException.class)
                .hasMessageContaining("client_id");
    }

    @Test
    void resolve_hostOnAPrivateAddress_isRefusedBeforeFetching() {
        Stub stub = new Stub("10.42.40.10", doc(URL, "https://app.example.com/cb"));

        assertThatThrownBy(() -> stub.resolve(URL)).isInstanceOf(InvalidClientException.class);
        assertThat(stub.fetches).hasValue(0);
    }

    @Test
    void resolve_unacceptableRedirect_isRejected() {
        Stub stub = new Stub("93.184.215.14", doc(URL, "http://evil.example/cb"));

        assertThatThrownBy(() -> stub.resolve(URL)).isInstanceOf(InvalidClientException.class);
    }

    @Test
    void checkUrl_requiresHttpsDefaultPortAndPath() {
        assertThatThrownBy(() -> ClientMetadataDocumentResolver.checkUrl("http://app.example.com/c.json"))
                .isInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> ClientMetadataDocumentResolver.checkUrl("https://app.example.com:8443/c.json"))
                .isInstanceOf(InvalidClientException.class);
        assertThatThrownBy(() -> ClientMetadataDocumentResolver.checkUrl("https://app.example.com/"))
                .isInstanceOf(InvalidClientException.class);
        assertThat(ClientMetadataDocumentResolver.checkUrl(URL).getHost()).isEqualTo("app.example.com");
    }

    @Test
    void isPublic_excludesInternalRanges() throws Exception {
        assertThat(ClientMetadataDocumentResolver.isPublic(InetAddress.getByName("93.184.215.14")))
                .isTrue();
        for (String internal : new String[] {
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "0.0.0.0",
            "::1",
            "fd00::1",
            "fe80::1"
        }) {
            assertThat(ClientMetadataDocumentResolver.isPublic(InetAddress.getByName(internal)))
                    .as(internal)
                    .isFalse();
        }
    }
}
