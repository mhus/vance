package de.mhus.vance.brain.milliways;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.mhus.vance.toolpack.mail.MailMessage;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire behaviour of {@link GmailApiClient}: what goes on the socket, and how
 * an answer that is not a 2xx is classified. The classification is the part
 * that matters downstream — it decides whether the sharer is told to fix
 * something or that Gmail broke, and therefore whether the audit entry says
 * {@code denied} or {@code failed}.
 */
class GmailApiClientTest {

    private static final String URL = "https://gmail.test/send?uploadType=media";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private HttpClient http;
    private GmailApiClient client;

    @BeforeEach
    void setUp() {
        http = mock(HttpClient.class);
        client = new GmailApiClient(MAPPER, http, URL);
    }

    @Test
    void send_postsTheRawMimeMessageWithTheBearerToken() throws Exception {
        givenResponse(200, "{\"id\":\"18f0\",\"threadId\":\"18f0\"}");

        Map<String, Object> result = client.send("tok-1", message());

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        HttpRequest request = sent.getValue();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri().toString()).isEqualTo(URL);
        assertThat(header(request, "Authorization")).isEqualTo("Bearer tok-1");
        // message/rfc822, not JSON: the body IS the message. The JSON
        // envelope form caps at 5 MB and base64s every attachment.
        assertThat(header(request, "Content-Type")).isEqualTo("message/rfc822");
        assertThat(result).containsEntry("id", "18f0").containsEntry("threadId", "18f0");
    }

    @Test
    void send_bodyIsAnAssembledMimeMessage() throws Exception {
        givenResponse(200, "{\"id\":\"1\"}");

        client.send("tok-1", message());

        ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(sent.capture(), any());
        assertThat(sent.getValue().bodyPublisher()).isPresent();
        assertThat(sent.getValue().bodyPublisher().get().contentLength()).isPositive();
    }

    @Test
    void send_401_isARefusalAndAnAuthFailure() throws Exception {
        givenResponse(401, "{\"error\":{\"message\":\"Invalid Credentials\"}}");

        assertThatThrownBy(() -> client.send("stale", message()))
                .isInstanceOfSatisfying(GmailApiClient.GmailException.class, e -> {
                    assertThat(e.refusal()).isTrue();
                    assertThat(e.authFailure()).isTrue();
                    // Google's own sentence, not our paraphrase of it.
                    assertThat(e).hasMessageContaining("Invalid Credentials");
                });
    }

    @Test
    void send_403_isAnAuthFailureToo() throws Exception {
        // Insufficient scope answers 403, and the fix is the same as for a
        // stale token: reconnect. Grouping them keeps the caller from
        // pattern-matching on a message string.
        givenResponse(403, "{\"error\":{\"message\":\"Request had insufficient scopes\"}}");

        assertThatThrownBy(() -> client.send("tok", message()))
                .isInstanceOfSatisfying(GmailApiClient.GmailException.class,
                        e -> assertThat(e.authFailure()).isTrue());
    }

    @Test
    void send_400_isARefusalButNotAnAuthFailure() throws Exception {
        givenResponse(400, "{\"error\":{\"message\":\"Invalid To header\"}}");

        assertThatThrownBy(() -> client.send("tok", message()))
                .isInstanceOfSatisfying(GmailApiClient.GmailException.class, e -> {
                    assertThat(e.refusal()).isTrue();
                    assertThat(e.authFailure()).isFalse();
                });
    }

    @Test
    void send_500_isNotARefusal() throws Exception {
        // Nothing the sharer can fix — Gmail broke.
        givenResponse(500, "{\"error\":{\"message\":\"Backend Error\"}}");

        assertThatThrownBy(() -> client.send("tok", message()))
                .isInstanceOfSatisfying(GmailApiClient.GmailException.class, e -> {
                    assertThat(e.refusal()).isFalse();
                    assertThat(e.status()).isEqualTo(500);
                });
    }

    @Test
    void send_networkFailure_isNotARefusal() throws Exception {
        when(http.send(any(), any())).thenThrow(new IOException("connection reset"));

        assertThatThrownBy(() -> client.send("tok", message()))
                .isInstanceOfSatisfying(GmailApiClient.GmailException.class, e -> {
                    assertThat(e.refusal()).isFalse();
                    assertThat(e.status()).isZero();
                });
    }

    @Test
    void send_nonJsonErrorPage_stillCarriesTheStatus() throws Exception {
        // A proxy's HTML page instead of Google's envelope: the status is the
        // part that matters, and the body must not blow up the parse.
        givenResponse(502, "<html><body>Bad Gateway</body></html>");

        assertThatThrownBy(() -> client.send("tok", message()))
                .isInstanceOfSatisfying(GmailApiClient.GmailException.class, e -> {
                    assertThat(e.status()).isEqualTo(502);
                    assertThat(e).hasMessageContaining("Bad Gateway");
                });
    }

    @Test
    void send_successWithoutIds_returnsAnEmptyMapRatherThanFailing() throws Exception {
        // A 2xx is a sent message. Missing ids cost the audit entry a line,
        // not the sharer their mail.
        givenResponse(204, "");

        assertThat(client.send("tok", message())).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void givenResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        // doReturn, not when(…).thenReturn(…): HttpClient.send is generic in
        // its body type, so the stub's inferred type is HttpResponse<Object>
        // and the typed mock does not fit it.
        doReturn(response).when(http).send(any(), any());
    }

    private static String header(HttpRequest request, String name) {
        Optional<String> value = request.headers().firstValue(name);
        return value.orElse(null);
    }

    private static MailMessage message() {
        return new MailMessage(
                List.of("ford@example.com"),
                null, null,
                "Results",
                "have a look",
                null, null, null,
                List.of(new MailMessage.Attachment(
                        "results.md", "text/markdown",
                        "# Results\n".getBytes(StandardCharsets.UTF_8))));
    }
}
