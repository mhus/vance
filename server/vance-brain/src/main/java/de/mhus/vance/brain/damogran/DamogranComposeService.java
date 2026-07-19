package de.mhus.vance.brain.damogran;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
    private final Map<String, ComposeRunner> runners;

    public DamogranComposeService(DamogranManifestParser parser, List<ComposeRunner> runnerBeans) {
        this.parser = parser;
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

    /** Dispatch a parsed compose manifest to the runner for its target. */
    public DamogranComposeResult run(
            String tenantId, String projectId, @Nullable String processId,
            DamogranManifest manifest, @Nullable String baseDir) {
        String target = manifest.workspace().target();
        ComposeRunner runner = runners.get(target);
        if (runner == null) {
            throw new DamogranException("compose target not supported: " + target
                    + " (available: " + new TreeSet<>(runners.keySet()) + ")");
        }
        return runner.run(tenantId, projectId, processId, manifest, baseDir);
    }
}
