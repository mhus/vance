package de.mhus.vance.brain.daemon;

import de.mhus.vance.api.tools.ClientToolInvokeRequest;
import de.mhus.vance.api.tools.ToolSpec;
import de.mhus.vance.api.ws.MessageType;
import de.mhus.vance.brain.tools.types.ToolFactory;
import de.mhus.vance.brain.ws.WebSocketSender;
import de.mhus.vance.shared.servertool.ServerToolDocument;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.ToolException;
import de.mhus.vance.toolpack.ToolInvocationContext;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code foot_daemon} — emits one sub-tool per entry of the daemon's
 * announced manifest. Sub-tools are named {@code <configName>__<subTool>}.
 * Invokes are routed via the WS attached to {@link DaemonRegistry} —
 * cross-session, no session bind needed.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code daemonName} (required) — project-unique identifier</li>
 *   <li>{@code exposedTools} (optional list) — whitelist; when present,
 *       only tools whose name appears here are surfaced</li>
 *   <li>{@code timeoutSeconds} (optional, default {@value #DEFAULT_TIMEOUT_SECONDS})
 *       — per-invocation upper bound; long-running exec uses the
 *       poll-based pattern, so this is meant for short calls only</li>
 * </ul>
 *
 * <p>When the daemon is offline at materialise-time, the factory returns
 * an empty list — the project's tool registry simply doesn't carry the
 * sub-tools until the daemon connects. After a reconnect the cache
 * invalidation in {@code DaemonRegistry}'s register/unregister path
 * causes the next discovery pass to pick up the fresh manifest (see
 * {@code FootDaemonCacheBridge}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FootDaemonToolFactory implements ToolFactory {

    public static final String TYPE_ID = "foot_daemon";

    static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private static final Map<String, Object> PARAMETERS_SCHEMA = Map.of(
            "type", "object",
            "required", List.of("daemonName"),
            "properties", Map.of(
                    "daemonName", Map.of("type", "string",
                            "description", "project-unique daemon identifier"),
                    "exposedTools", Map.of("type", "array",
                            "items", Map.of("type", "string"),
                            "description", "optional whitelist of sub-tool names; "
                                    + "when set, only listed tools are surfaced"),
                    "timeoutSeconds", Map.of("type", "integer",
                            "description", "per-invocation upper bound (default "
                                    + DEFAULT_TIMEOUT_SECONDS + "s)")));

    private final DaemonRegistry daemonRegistry;
    private final WebSocketSender sender;

    @Override public String typeId() { return TYPE_ID; }
    @Override public Map<String, Object> parametersSchema() { return PARAMETERS_SCHEMA; }

    @Override
    public Collection<Tool> create(ServerToolDocument document) {
        return create(document, null);
    }

    @Override
    public Collection<Tool> create(
            ServerToolDocument document, @Nullable ToolInvocationContext ctx) {
        Map<String, Object> params = document.getParameters() == null
                ? Map.of() : document.getParameters();
        Object daemonNameRaw = params.get("daemonName");
        if (!(daemonNameRaw instanceof String dn) || dn.isBlank()) {
            throw new IllegalArgumentException(
                    "foot_daemon '" + document.getName() + "': parameters.daemonName is required");
        }
        Set<String> whitelist = stringSet(params.get("exposedTools"));
        int timeoutSeconds = intOr(params.get("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);

        DaemonRegistry.DaemonKey key = new DaemonRegistry.DaemonKey(
                document.getTenantId(), document.getProjectId(), dn.trim());

        // Offline daemon → no sub-tools surfaced. A future discovery pass
        // after register() (or the registry's stale-sweep dropping the
        // entry) will keep this list in sync. Stale daemons DO surface
        // their sub-tools so listings stay stable during reconnect
        // flicker; invoke-time the proxy distinguishes online from stale.
        var refOpt = daemonRegistry.find(key);
        if (refOpt.isEmpty()) {
            log.debug("FootDaemonToolFactory '{}' daemon='{}' offline — no sub-tools",
                    document.getName(), key);
            return List.of();
        }
        DaemonRegistry.DaemonRef ref = refOpt.get();
        Map<String, ToolSpec> manifest = ref.manifest();
        Set<String> labels = labelsFor(document);
        boolean primary = document.isPrimary();
        boolean deferred = document.isDefaultDeferred();
        String promptHint = document.getPromptHint() == null ? "" : document.getPromptHint();

        List<Tool> out = new ArrayList<>(manifest.size());
        for (Map.Entry<String, ToolSpec> e : manifest.entrySet()) {
            String subToolName = e.getKey();
            if (!whitelist.isEmpty() && !whitelist.contains(subToolName)) continue;
            out.add(new DaemonProxyTool(
                    document.getName(), subToolName, e.getValue(),
                    key, labels, primary, deferred, promptHint,
                    Duration.ofSeconds(timeoutSeconds)));
        }
        log.info("FootDaemonToolFactory '{}' tenant='{}' project='{}' daemon='{}'{} produced {}/{} tools",
                document.getName(), document.getTenantId(), document.getProjectId(),
                key.daemonName(), ref.stale() ? " (stale)" : "",
                out.size(), manifest.size());
        return out;
    }

    private static int intOr(@Nullable Object v, int fallback) {
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static Set<String> stringSet(@Nullable Object v) {
        if (!(v instanceof List<?> list)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o instanceof String s && !s.isBlank()) out.add(s.trim());
        }
        return out;
    }

    private static Set<String> labelsFor(ServerToolDocument doc) {
        Set<String> out = new LinkedHashSet<>();
        if (doc.getLabels() != null) out.addAll(doc.getLabels());
        out.add(TYPE_ID + ":" + doc.getName());
        return out;
    }

    /** One sub-tool wrapping a daemon-side ToolSpec. */
    private final class DaemonProxyTool implements Tool {

        private final String fullName;
        private final ToolSpec spec;
        private final DaemonRegistry.DaemonKey daemonKey;
        private final Set<String> labels;
        private final boolean primary;
        private final boolean deferred;
        private final String promptHint;
        private final Duration timeout;

        DaemonProxyTool(
                String packName, String subToolName, ToolSpec spec,
                DaemonRegistry.DaemonKey daemonKey,
                Set<String> labels, boolean primary, boolean deferred,
                String promptHint, Duration timeout) {
            this.fullName = packName + ToolFactory.PACK_SEPARATOR + subToolName;
            this.spec = spec;
            this.daemonKey = daemonKey;
            this.labels = labels;
            this.primary = primary;
            this.deferred = deferred;
            this.promptHint = promptHint;
            this.timeout = timeout;
        }

        @Override public String name() { return fullName; }
        @Override public String description() {
            String d = spec.getDescription();
            return d == null ? "" : d;
        }
        @Override public boolean primary() { return primary; }
        @Override public boolean deferred() { return deferred; }
        @Override public Set<String> labels() { return labels; }
        @Override public String promptHint() { return promptHint; }

        @Override
        public Map<String, Object> paramsSchema() {
            Map<String, Object> schema = spec.getParamsSchema();
            return schema == null ? Map.of("type", "object", "properties", Map.of()) : schema;
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> params, ToolInvocationContext ctx) {
            // Re-resolve at call time so a daemon that disappeared between
            // materialise and invoke produces a fresh, accurate error
            // instead of trying to write to a stale WS.
            var refOpt = daemonRegistry.find(daemonKey);
            if (refOpt.isEmpty()) {
                throw new ToolException("daemon '" + daemonKey.daemonName()
                        + "' is offline in project '" + daemonKey.projectId() + "'");
            }
            var ref = refOpt.get();
            if (ref.stale()) {
                // Listed but unreachable — give the chat agent enough context
                // to either wait + retry or apologise to the user.
                String since = ref.disconnectedAt() == null
                        ? "recently" : "since " + ref.disconnectedAt();
                throw new ToolException("daemon '" + daemonKey.daemonName()
                        + "' is offline " + since
                        + " — sub-tools are still listed but unreachable until reconnect");
            }
            String subToolName = spec.getName();
            DaemonRegistry.DaemonPending pending = daemonRegistry.beginInvocation(daemonKey, subToolName);
            try {
                ClientToolInvokeRequest invoke = ClientToolInvokeRequest.builder()
                        .correlationId(pending.correlationId())
                        .name(subToolName)
                        .params(params == null ? Map.of() : params)
                        .build();
                sender.sendNotification(ref.wsSession(), MessageType.CLIENT_TOOL_INVOKE, invoke);
            } catch (IOException ioe) {
                daemonRegistry.cancel(pending.correlationId(),
                        "failed to write invoke envelope: " + ioe.getMessage());
                throw new ToolException("daemon invoke send failed: " + ioe.getMessage(), ioe);
            }
            try {
                return pending.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                daemonRegistry.cancel(pending.correlationId(),
                        "daemon '" + daemonKey.daemonName() + "' timed out after " + timeout);
                throw new ToolException("daemon '" + daemonKey.daemonName()
                        + "' did not respond within " + timeout, te);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                daemonRegistry.cancel(pending.correlationId(), "invoke interrupted");
                throw new ToolException("daemon invoke interrupted", ie);
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause() == null ? ee : ee.getCause();
                throw new ToolException(cause.getMessage() == null
                        ? "daemon invoke failed" : cause.getMessage(), cause);
            }
        }
    }
}
