package de.mhus.vance.brain.centauri.protocols;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records what a protocol put on the wire and answers with canned bodies,
 * matched by URL path suffix. Shared by the protocol tests — the alternative
 * was the same forty lines three times.
 */
final class RecordingHttpClient implements CentauriHttpClient {

    record Call(String method, URI url, Map<String, String> headers, String body) {

        /**
         * The query in <b>decoded</b> form — {@code URI.getQuery()} undoes the
         * percent-escaping, so assert against {@code a=b:c|d} rather than
         * {@code a=b%3Ac%7Cd}. Assert on {@link #url()} when the encoding itself
         * is the point.
         */
        String query() {
            return url.getQuery() == null ? "" : url.getQuery();
        }
    }

    private final Map<String, Response> replies = new LinkedHashMap<>();
    private final List<Call> calls = new ArrayList<>();

    RecordingHttpClient reply(String pathSuffix, int status, String body) {
        replies.put(pathSuffix, new Response(status, body));
        return this;
    }

    /** Answers every path — for protocols that only ever call one endpoint. */
    RecordingHttpClient replyAny(int status, String body) {
        return reply("", status, body);
    }

    Call last() {
        if (calls.isEmpty()) {
            throw new AssertionError("no HTTP call was made");
        }
        return calls.get(calls.size() - 1);
    }

    int callCount() {
        return calls.size();
    }

    @Override
    public Response get(URI url, Map<String, String> headers, Duration timeout) {
        calls.add(new Call("GET", url, headers, ""));
        return match(url);
    }

    @Override
    public Response postJson(URI url, String body, Map<String, String> headers, Duration timeout) {
        calls.add(new Call("POST", url, headers, body));
        return match(url);
    }

    private Response match(URI url) {
        String path = url.getPath() == null ? "" : url.getPath();
        for (Map.Entry<String, Response> entry : replies.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return new Response(404, "");
    }
}
