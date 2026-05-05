package de.mhus.vance.foot.tools.js;

import de.mhus.vance.foot.script.ClientExecutionContext;
import de.mhus.vance.foot.script.ClientScriptExecutionException;
import de.mhus.vance.foot.script.ClientScriptExecutor;
import de.mhus.vance.foot.script.ClientScriptRequest;
import de.mhus.vance.foot.script.ClientScriptResult;
import de.mhus.vance.foot.session.SessionService;
import de.mhus.vance.foot.tools.ClientTool;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code client_javascript} — runs a JS snippet on the foot host. The
 * script sees the {@code client} host binding and may call other
 * registered client tools through {@code client.tools.call(...)}.
 */
@Component
@RequiredArgsConstructor
public class ClientJavascriptTool implements ClientTool {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "code", Map.of(
                            "type", "string",
                            "description",
                                    "JavaScript source. The value of the LAST "
                                            + "expression is returned."),
                    "timeoutMs", Map.of(
                            "type", "integer",
                            "description",
                                    "Wall-clock timeout in milliseconds (default 5000, max 30000).")),
            "required", List.of("code"));

    private final ClientScriptExecutor executor;
    private final SessionService sessionService;

    @Override
    public String name() {
        return "client_javascript";
    }

    @Override
    public String description() {
        return "Execute JavaScript on the user's machine (foot client). "
                + "The host binding 'client' exposes client.tools.call(name, params), "
                + "client.context (request id, session) and client.log. "
                + "Use this for fast local computation in the user's environment.";
    }

    @Override
    public boolean primary() {
        return false;
    }

    @Override
    public Map<String, Object> paramsSchema() {
        return SCHEMA;
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("code");
        if (!(raw instanceof String code) || code.isBlank()) {
            throw new IllegalArgumentException(
                    "'code' is required and must be a non-empty string");
        }
        Duration timeout = resolveTimeout(params);
        SessionService.BoundSession bound = sessionService.current();
        ClientExecutionContext ctx = new ClientExecutionContext(
                UUID.randomUUID().toString(),
                bound == null ? null : bound.sessionId(),
                bound == null ? null : bound.projectId());
        try {
            ClientScriptResult result = executor.run(
                    new ClientScriptRequest("js", code, "client_javascript", ctx, timeout));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("value", result.value());
            out.put("durationMs", result.duration().toMillis());
            return out;
        } catch (ClientScriptExecutionException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.errorClass().name());
            out.put("message", e.getMessage());
            return out;
        }
    }

    private static Duration resolveTimeout(Map<String, Object> params) {
        if (params == null) {
            return DEFAULT_TIMEOUT;
        }
        Object raw = params.get("timeoutMs");
        if (raw instanceof Number n) {
            long ms = Math.max(1, Math.min(MAX_TIMEOUT.toMillis(), n.longValue()));
            return Duration.ofMillis(ms);
        }
        return DEFAULT_TIMEOUT;
    }
}
