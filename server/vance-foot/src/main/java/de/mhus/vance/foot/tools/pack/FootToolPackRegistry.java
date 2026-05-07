package de.mhus.vance.foot.tools.pack;

import de.mhus.vance.foot.tools.ClientToolService;
import de.mhus.vance.toolpack.Tool;
import de.mhus.vance.toolpack.core.PackHttpClient;
import de.mhus.vance.toolpack.mcp.McpPackBuilder;
import de.mhus.vance.toolpack.rest.RestApiPackBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Loads {@code ~/.vance/foot-tools/*.json} into runnable
 * {@link Tool}s and pushes them through {@link ClientToolService}
 * so the brain sees a single registration containing both the
 * hand-coded {@code @Component ClientTool} beans and the JSON-defined
 * pack tools.
 *
 * <p>Boot lifecycle: a {@code @PostConstruct} kicks off a background
 * thread that does the load + materialise + register. Foot doesn't
 * block on it — the 8 hand-coded tools are already registered
 * separately on session-bind; pack tools land asynchronously and
 * trigger a re-register the moment they're ready.
 *
 * <p>{@link #reload()} is the slash-command entry point. It runs
 * synchronously (the user typed {@code /tools reload} and expects
 * a status line) and replaces the current pack-tool set on
 * {@link ClientToolService}, closing any stale MCP connections /
 * HTTP-Keep-Alives that belonged to the old set.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FootToolPackRegistry {

    private final FootToolPackLoader loader;
    private final EnvSecretResolver secretResolver;
    private final ClientToolService clientToolService;

    /**
     * Single shared HTTP client for every REST/MCP-HTTP pack — pools
     * connections across packs that hit the same host. New TLS
     * configurations get their own internal {@code HttpClient}
     * inside this wrapper.
     */
    private final PackHttpClient httpClient = new PackHttpClient();

    /**
     * Reference to the currently-active pack output. Held so we can
     * close lifecycle-bearing tools (MCP transports) when the next
     * reload supersedes them.
     */
    private final AtomicReference<List<Tool>> activePackTools = new AtomicReference<>(List.of());

    /**
     * Last reload status line — surfaced by the slash command and
     * useful in diagnostic logs. Not a structured result type yet;
     * upgrade when there's UI need.
     */
    private final AtomicReference<String> lastStatus = new AtomicReference<>("(not loaded yet)");

    @PostConstruct
    void bootLoadAsync() {
        Thread loader = new Thread(() -> {
            try {
                String status = doLoadAndRegister();
                log.info("FootToolPackRegistry boot-load: {}", status);
            } catch (RuntimeException e) {
                log.warn("FootToolPackRegistry boot-load failed: {}", e.toString());
                lastStatus.set("boot-load failed: " + e.getMessage());
            }
        }, "foot-tool-pack-loader");
        loader.setDaemon(true);
        loader.start();
    }

    /**
     * Slash-command entry point — re-reads the pack files, rebuilds
     * the tool list, and re-registers with the brain. Runs
     * synchronously; safe to call from the JLine REPL thread.
     *
     * @return status line: {@code "loaded N pack(s) → M tool(s); K skipped"}
     */
    public String reload() {
        return doLoadAndRegister();
    }

    /** Last reload status — what the slash command would print if called now. */
    public String lastStatus() {
        return lastStatus.get();
    }

    @PreDestroy
    void shutdown() {
        closeActiveTools();
    }

    // ─────── Internals ───────

    private String doLoadAndRegister() {
        List<FootToolPackConfig> configs = loader.loadAll();
        if (configs.isEmpty()) {
            closeActiveTools();
            activePackTools.set(List.of());
            clientToolService.setPackTools(List.of());
            String s = "no foot tool packs found in " + describeDir();
            lastStatus.set(s);
            return s;
        }

        int active = 0;
        int skipped = 0;
        int failed = 0;
        List<Tool> nextTools = new ArrayList<>();
        for (FootToolPackConfig config : configs) {
            if (!config.isEffectivelyEnabled()) {
                log.info("FootToolPackRegistry: pack '{}' disabled (enabled=false)", config.name());
                skipped++;
                continue;
            }
            try {
                Collection<Tool> tools = materialise(config);
                nextTools.addAll(tools);
                active++;
                log.info("FootToolPackRegistry: pack '{}' (type={}) → {} tool(s)",
                        config.name(), config.type(), tools.size());
            } catch (RuntimeException e) {
                failed++;
                log.warn("FootToolPackRegistry: pack '{}' (type={}) failed to materialise: {}",
                        config.name(), config.type(), e.toString());
            }
        }
        // Drop life-cycle resources from the previous set first, then
        // swap in the new one. ClientToolService's setPackTools triggers
        // a re-register against the brain.
        closeActiveTools();
        activePackTools.set(List.copyOf(nextTools));
        clientToolService.setPackTools(nextTools);

        String status = String.format(
                "loaded %d pack%s → %d tool%s; %d skipped, %d failed",
                active, active == 1 ? "" : "s",
                nextTools.size(), nextTools.size() == 1 ? "" : "s",
                skipped, failed);
        lastStatus.set(status);
        return status;
    }

    private Collection<Tool> materialise(FootToolPackConfig config) {
        switch (config.type()) {
            case "rest_api" -> {
                RestApiPackBuilder.PackInput input = new RestApiPackBuilder.PackInput(
                        config.name(),
                        config.labelsAsSet(),
                        config.primary(),
                        config.defaultDeferred(),
                        config.parametersOrEmpty());
                Collection<Tool> tools = RestApiPackBuilder.build(input, httpClient, secretResolver);
                return filterDisabledSubTools(tools, config);
            }
            case "mcp_server" -> {
                McpPackBuilder.PackInput input = new McpPackBuilder.PackInput(
                        config.name(),
                        config.labelsAsSet(),
                        config.primary(),
                        config.defaultDeferred(),
                        config.parametersOrEmpty());
                // Foot has no tenant/session — the bootstrap context is empty.
                de.mhus.vance.toolpack.ToolInvocationContext bootstrap =
                        new de.mhus.vance.toolpack.ToolInvocationContext("", null, null, null, null);
                Collection<Tool> tools = McpPackBuilder.build(input, httpClient, secretResolver, bootstrap);
                return filterDisabledSubTools(tools, config);
            }
            default -> throw new IllegalArgumentException(
                    "foot tool pack '" + config.name() + "': unsupported type '"
                            + config.type() + "' — supported: rest_api, mcp_server");
        }
    }

    /**
     * Drops sub-tools listed in {@link FootToolPackConfig#disabledSubToolsAsSet()}
     * — match against the local sub-name (everything after {@code <pack>__}).
     */
    private static Collection<Tool> filterDisabledSubTools(
            Collection<Tool> tools, FootToolPackConfig config) {
        java.util.Set<String> disabled = config.disabledSubToolsAsSet();
        if (disabled.isEmpty()) return tools;
        String prefix = config.name() + "__";
        List<Tool> kept = new ArrayList<>();
        for (Tool t : tools) {
            String localName = t.name().startsWith(prefix)
                    ? t.name().substring(prefix.length()) : t.name();
            if (!disabled.contains(localName)) kept.add(t);
        }
        return kept;
    }

    private void closeActiveTools() {
        List<Tool> previous = activePackTools.getAndSet(List.of());
        for (Tool t : previous) {
            // McpEndpointTool wraps an McpConnection; closing the tool
            // is a no-op, but the connection itself implements
            // AutoCloseable and should release subprocess / HTTP. The
            // current MCP path holds connections via the McpPackBuilder
            // which keeps a single connection per pack; future
            // refactor: track the connection list here directly so we
            // can close them. For v1 the GC + JVM shutdown handle it.
            if (t instanceof AutoCloseable closeable) {
                try { closeable.close(); }
                catch (Exception e) {
                    log.debug("FootToolPackRegistry: close failed for tool '{}': {}",
                            t.name(), e.toString());
                }
            }
        }
    }

    private String describeDir() {
        java.nio.file.Path dir = loader.effectiveDir();
        return dir == null ? "(no directory configured)" : dir.toString();
    }
}
