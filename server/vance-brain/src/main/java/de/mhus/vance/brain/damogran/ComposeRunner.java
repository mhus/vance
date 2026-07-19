package de.mhus.vance.brain.damogran;

import org.jspecify.annotations.Nullable;

/**
 * Per-target executor for a parsed compose manifest — one bean per
 * {@link DamogranManifest.WorkspaceSpec#target()} (WORK / CLIENT / DAEMON),
 * resolved by {@link DamogranComposeService} (the open-registry SPI pattern
 * used across Damogran). The target choice happens once, at dispatch — no
 * {@code if (isClient)} branching leaks into tasks, transport or the loop.
 *
 * <p>Each runner owns its own lifecycle: WORK provisions a managed server-side
 * RootDir; CLIENT runs against the connected Foot's filesystem (no managed
 * workspace, exec + file only). What is genuinely shared — reading an import
 * source ({@code vance:}/{@code http:}/{@code git:}) and the output-artifact
 * model — lives in helpers, not a forced common loop.
 */
public interface ComposeRunner {

    /** The {@code workspace.target} this runner handles (e.g. {@code WORK}). */
    String target();

    /** Synchronous run (no progress tracking). */
    default DamogranComposeResult run(String tenantId, String projectId, @Nullable String processId,
                                      DamogranManifest manifest, @Nullable String baseDir) {
        return run(tenantId, projectId, processId, manifest, baseDir, null);
    }

    /**
     * Execute {@code manifest} against this runner's target.
     *
     * @param processId the bound process (WorkTarget carrier + tool surface),
     *                  or {@code null} for a process-less run
     * @param baseDir   directory of the compose document, for resolving relative
     *                  {@code vance:} import/export paths ({@code null} = root)
     * @param run       async-run state for progress reporting, or {@code null}
     *                  for a synchronous run
     */
    DamogranComposeResult run(String tenantId, String projectId, @Nullable String processId,
                              DamogranManifest manifest, @Nullable String baseDir,
                              @Nullable ComposeRun run);
}
