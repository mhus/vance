package de.mhus.vance.aitest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Background thread that auto-answers PENDING inbox items so the test
 * doesn't get stuck behind workers asking for user input.
 *
 * <p>Polls {@code GET /brain/{tenant}/inbox?status=PENDING} every
 * {@link #pollInterval} via the brain REST API (authenticated with the
 * JWT minted by {@link BrainAuthClient}), then for each item that
 * {@link #shouldAnswer(Map) requires action} it POSTs a default-shaped
 * answer to {@code /brain/{tenant}/inbox/{id}/answer}.
 *
 * <p>Default answer per type (always {@code outcome=DECIDED}):
 * <ul>
 *   <li>{@code APPROVAL} → {@code {"approved": true}}</li>
 *   <li>{@code DECISION} → first option from {@code payload.options} (or
 *       {@code payload.default}); if neither is present, sends
 *       {@code {"chosen": "yes"}}</li>
 *   <li>{@code FEEDBACK} → {@code {"text": "Yes, proceed."}}</li>
 *   <li>everything else (OUTPUT_*, ORDERING, STRUCTURE_EDIT) is ignored
 *       — those don't block workers.</li>
 * </ul>
 *
 * <p>Every answer is recorded in {@link #answered()} so the test can
 * assert on what it auto-resolved (and post-mortem any unexpected
 * questions). Errors are caught and logged to stderr — the responder
 * never throws back to the test loop.
 */
public final class InboxAutoResponder implements AutoCloseable {

    private final BrainAuthClient auth;
    private final Duration pollInterval;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<AnsweredItem> answered = new CopyOnWriteArrayList<>();
    private volatile Thread worker;

    public InboxAutoResponder(BrainAuthClient auth) {
        this(auth, Duration.ofSeconds(2));
    }

    public InboxAutoResponder(BrainAuthClient auth, Duration pollInterval) {
        this.auth = auth;
        this.pollInterval = pollInterval;
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        Thread t = new Thread(this::loop, "inbox-auto-responder");
        t.setDaemon(true);
        t.start();
        this.worker = t;
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Snapshot of items the responder has answered so far. */
    public List<AnsweredItem> answered() {
        return new ArrayList<>(answered);
    }

    private void loop() {
        while (running.get()) {
            try {
                List<Map<String, Object>> items = listPending();
                for (Map<String, Object> item : items) {
                    if (!running.get()) {
                        break;
                    }
                    if (!shouldAnswer(item)) {
                        continue;
                    }
                    answerOne(item);
                }
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("[inbox-auto-responder] " + e.getMessage());
                try {
                    Thread.sleep(pollInterval.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private List<Map<String, Object>> listPending() throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(
                                auth.baseUrl() + "/brain/" + auth.tenant()
                                        + "/inbox?status=PENDING"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + auth.token())
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("inbox list failed (HTTP " + r.statusCode()
                    + "): " + r.body());
        }
        Map<String, Object> body = json.readValue(r.body(), Map.class);
        Object items = body.get("items");
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                out.add(casted);
            }
        }
        return out;
    }

    private boolean shouldAnswer(Map<String, Object> item) {
        if (!Boolean.TRUE.equals(item.get("requiresAction"))) {
            return false;
        }
        String type = String.valueOf(item.get("type"));
        return "APPROVAL".equals(type) || "DECISION".equals(type) || "FEEDBACK".equals(type);
    }

    private void answerOne(Map<String, Object> item) throws IOException, InterruptedException {
        String id = String.valueOf(item.get("id"));
        String type = String.valueOf(item.get("type"));
        Map<String, Object> value = defaultValueFor(type, item);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("itemId", id);
        request.put("outcome", "DECIDED");
        request.put("value", value);

        HttpResponse<String> r = http.send(
                HttpRequest.newBuilder(URI.create(
                                auth.baseUrl() + "/brain/" + auth.tenant()
                                        + "/inbox/" + id + "/answer"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(request)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            System.err.println("[inbox-auto-responder] answer " + id
                    + " failed (HTTP " + r.statusCode() + "): " + r.body());
            return;
        }
        answered.add(new AnsweredItem(
                Instant.now(), id, type,
                String.valueOf(item.getOrDefault("title", "")),
                value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> defaultValueFor(String type, Map<String, Object> item) {
        Map<String, Object> payload = item.get("payload") instanceof Map<?, ?> p
                ? (Map<String, Object>) p
                : Map.of();
        return switch (type) {
            case "APPROVAL" -> Map.of("approved", true);
            case "DECISION" -> {
                Object def = payload.get("default");
                if (def != null) {
                    yield Map.of("chosen", def);
                }
                Object opts = payload.get("options");
                if (opts instanceof List<?> l && !l.isEmpty()) {
                    yield Map.of("chosen", l.get(0));
                }
                yield Map.of("chosen", "yes");
            }
            case "FEEDBACK" -> Map.of("text", "Yes, proceed.");
            default -> Map.of();
        };
    }

    /** Audit row of one auto-answered item — handy for test post-mortems. */
    public record AnsweredItem(
            Instant at, String itemId, String type, String title,
            Map<String, Object> value) {}
}
