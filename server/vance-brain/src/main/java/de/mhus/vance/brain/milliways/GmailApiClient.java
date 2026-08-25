package de.mhus.vance.brain.milliways;

import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.mail.MailMessage;
import jakarta.mail.MessagingException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place that talks to the Gmail API — the {@code *Client} convention
 * for outbound REST to a foreign system.
 *
 * <p>Uses the <b>media-upload</b> form of {@code users.messages.send}: the
 * request body <em>is</em> the RFC-5322 message, {@code Content-Type:
 * message/rfc822}. The alternative — a JSON envelope with the message
 * base64url-encoded into a {@code raw} field — caps out at 5 MB and would
 * make every attachment a third larger on the wire for no gain. Same
 * endpoint, same response, fewer moving parts.
 *
 * <p>Stateless w.r.t. identity: the access token arrives on every call, so
 * one bean serves every tenant and user. Obtaining and refreshing that
 * token is {@code OAuthTokenRefresher}'s job, not this class's.
 */
@Component
@Slf4j
public class GmailApiClient {

    /**
     * Media-upload endpoint of {@code users.messages.send}. {@code me}
     * resolves to whoever the token belongs to — there is deliberately no
     * way to address another mailbox from here.
     */
    public static final String DEFAULT_SEND_URL =
            "https://gmail.googleapis.com/upload/gmail/v1/users/me/messages/send"
                    + "?uploadType=media";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper;
    private final HttpClient http;
    private final String sendUrl;

    @Autowired
    public GmailApiClient(ObjectMapper objectMapper) {
        this(objectMapper,
                new PackHttpClient(CONNECT_TIMEOUT).client(PackHttpClient.TlsConfig.DEFAULT),
                DEFAULT_SEND_URL);
    }

    GmailApiClient(ObjectMapper objectMapper, HttpClient http, String sendUrl) {
        this.objectMapper = objectMapper;
        this.http = http;
        this.sendUrl = sendUrl;
    }

    /**
     * Sends the message as the account the token belongs to.
     *
     * <p>The From header is left to Google: it fills in the account's
     * address, and a header we invent is either redundant or rejected as an
     * unregistered send-as alias.
     *
     * @return {@code id} and {@code threadId} of the created message
     * @throws GmailException on any non-2xx answer or transport failure;
     *         {@link GmailException#refusal()} separates "the request was
     *         wrong" from "the far side broke"
     */
    public Map<String, Object> send(String accessToken, MailMessage message) {
        byte[] mime;
        try {
            mime = message.toRfc822Bytes(/*fromAddr*/ null);
        } catch (MessagingException | IOException e) {
            // Assembling a message we built ourselves failed — a bad
            // filename or an unencodable header, not a Gmail problem.
            throw new GmailException(
                    "Could not assemble the message: " + e.getMessage(), 0, true, e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(sendUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", PackHttpClient.bearerAuthHeader(accessToken))
                .header("Content-Type", "message/rfc822")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mime))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GmailException("Gmail is not reachable: " + e.getMessage(), 0, false, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GmailException("Interrupted while sending through Gmail", 0, false, e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            // A 4xx is Google telling us the request was wrong — expired
            // token, missing scope, a From alias that is not registered. A
            // 5xx is Google being unavailable. The caller maps those to
            // different outcomes, so the distinction travels with the
            // exception rather than being re-derived from a message string.
            throw new GmailException(
                    "Gmail refused the message (HTTP " + status + "): " + errorMessage(response),
                    status, status < 500, null);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode body = parse(response.body());
        if (body != null) {
            putIfText(out, body, "id");
            putIfText(out, body, "threadId");
        }
        return out;
    }

    // ──────────────────── internals ────────────────────

    /**
     * The one sentence out of Google's error envelope
     * ({@code {"error":{"message":"…"}}}) that says what to fix. Falls back
     * to the raw body, capped: an HTML error page in an audit entry helps
     * nobody, and an uncapped one is a payload.
     */
    private String errorMessage(HttpResponse<String> response) {
        JsonNode body = parse(response.body());
        if (body != null) {
            JsonNode message = body.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) {
                return message.asText();
            }
        }
        String raw = response.body() == null ? "" : response.body().strip();
        if (raw.isEmpty()) return "no details";
        return raw.length() <= 500 ? raw : raw.substring(0, 500) + "…";
    }

    private @Nullable JsonNode parse(@Nullable String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            // Not JSON — a proxy's HTML error page, most likely. The caller
            // still has the status code, which is the part that matters.
            log.debug("Gmail answered with a non-JSON body: {}", e.toString());
            return null;
        }
    }

    private static void putIfText(Map<String, Object> out, JsonNode body, String field) {
        JsonNode node = body.path(field);
        if (node.isTextual() && !node.asText().isBlank()) {
            out.put(field, node.asText());
        }
    }

    /**
     * Gmail said no, or could not be asked.
     *
     * <p>{@code refusal} is the whole point of the type: it decides whether
     * the sharer sees "fix this" or "we tried and it broke", and therefore
     * whether the audit entry reads {@code denied} or {@code failed}.
     */
    public static class GmailException extends RuntimeException {

        private final int status;
        private final boolean refusal;

        public GmailException(
                String message, int status, boolean refusal, @Nullable Throwable cause) {
            super(message, cause);
            this.status = status;
            this.refusal = refusal;
        }

        /** HTTP status, or {@code 0} when the call never got an answer. */
        public int status() {
            return status;
        }

        /** {@code true} when the request was wrong rather than the far side. */
        public boolean refusal() {
            return refusal;
        }

        /** {@code true} when the token is gone or lacks the mail scope. */
        public boolean authFailure() {
            return status == 401 || status == 403;
        }
    }
}
