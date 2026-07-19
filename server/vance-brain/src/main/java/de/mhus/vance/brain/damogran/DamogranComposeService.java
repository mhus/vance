package de.mhus.vance.brain.damogran;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Entry point for running a Damogran compose. Parses the manifest and
 * dispatches to the {@link ComposeRunner} registered for its
 * {@code workspace.target} (WORK / CLIENT / DAEMON) — the target choice happens
 * here, once, so no downstream code branches on it.
 *
 * <p>Linear by design (no loops/gates/branches — that is Vogon/Magrathea);
 * each runner owns its own lifecycle. See {@code planning/damogran-system.md}.
 */
@Slf4j
@Service
public class DamogranComposeService {

    private final DamogranManifestParser parser;
    private final ComposeRunRegistry runRegistry;
    private final Map<String, ComposeRunner> runners;
    private final ExecutorService asyncRunners = Executors.newVirtualThreadPerTaskExecutor();

    public DamogranComposeService(DamogranManifestParser parser,
                                  ComposeRunRegistry runRegistry,
                                  List<ComposeRunner> runnerBeans) {
        this.parser = parser;
        this.runRegistry = runRegistry;
        Map<String, ComposeRunner> byTarget = new HashMap<>();
        for (ComposeRunner runner : runnerBeans) {
            byTarget.put(runner.target(), runner);
        }
        this.runners = Map.copyOf(byTarget);
        log.debug("DamogranComposeService: compose runners for target(s) {}",
                new TreeSet<>(this.runners.keySet()));
    }

    /** Parse and run a compose manifest given as YAML text. */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId, String yaml) {
        return run(tenantId, projectId, processId, yaml, null);
    }

    /**
     * Parse and run a compose YAML.
     *
     * @param baseDir directory of the compose document, for resolving relative
     *                {@code vance:} import/export paths ({@code null} = root-relative)
     */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            String yaml, @Nullable String baseDir) {
        return run(tenantId, projectId, processId, parser.parse(yaml), baseDir);
    }

    /** Run a parsed compose manifest (root-relative {@code vance:} paths). */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId, DamogranManifest manifest) {
        return run(tenantId, projectId, processId, manifest, null);
    }

    /** Dispatch a parsed compose manifest to the runner for its target (synchronous). */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir) {
        return dispatch(tenantId, projectId, processId, manifest, baseDir, null);
    }

    // ──────────────────── async ────────────────────

    /** Parse and start an async compose run; returns the (registered) {@link ComposeRun}. */
    public ComposeRun runAsync(
            String tenantId, String projectId, @Nullable String processId,
            String yaml, @Nullable String baseDir) {
        return runAsync(tenantId, projectId, processId, parser.parse(yaml), baseDir);
    }

    /**
     * Start a compose run on a background virtual thread and return its
     * {@link ComposeRun} immediately (registered for polling). The run keeps
     * going after this returns — callers wait a fast-path budget for a quick
     * result, else poll by {@code runId}. In-pod only (a pod restart loses it).
     */
    public ComposeRun runAsync(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir) {
        String runId = "cr-" + UUID.randomUUID().toString().substring(0, 8);
        ComposeRun run = new ComposeRun(
                runId, tenantId, projectId, manifest.workspace().name(), Instant.now());
        runRegistry.register(run);
        asyncRunners.submit(() -> {
            try {
                run.complete(dispatch(tenantId, projectId, processId, manifest, baseDir, run));
            } catch (RuntimeException e) {
                log.warn("Damogran async compose '{}' failed: {}", runId, e.toString());
                run.fail(e.getMessage());
            }
        });
        return run;
    }

    private DamogranComposeResult dispatch(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir, @Nullable ComposeRun run) {
        String target = manifest.workspace().target();
        ComposeRunner runner = runners.get(target);
        if (runner == null) {
            throw new DamogranException("compose target not supported: " + target
                    + " (available: " + new TreeSet<>(runners.keySet()) + ")");
        }
        return runner.run(tenantId, projectId, processId, manifest, baseDir, run);
    }
}
