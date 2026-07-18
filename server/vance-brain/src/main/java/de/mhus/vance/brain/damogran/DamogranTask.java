package de.mhus.vance.brain.damogran;

import de.mhus.vance.brain.damogran.DamogranManifest.TaskSpec;

/**
 * SPI for a Damogran task type. One Spring bean per {@link #type()}; the
 * {@link DamogranTaskExecutor} builds a {@code type → bean} registry from all
 * beans on the classpath (built-in and addon-provided).
 *
 * <p>This is the addon-extension point (open registry, like
 * {@code Tex2PdfExecutor} / {@code SearchProtocol} / {@code KindHandler}) —
 * deliberately <em>not</em> a {@code TriggerAction} variant, since
 * {@code TriggerAction} is a {@code sealed} interface in {@code vance-api} that
 * addons cannot extend. Built-in task beans (exec / js / python / spawn / llm)
 * delegate <em>down</em> to the shared {@code ActionExecutorRegistry} /
 * {@code LightLlmService}; domain beans (e.g. {@code tex-task}) live in their
 * addon and call their own service.
 */
public interface DamogranTask {

    /**
     * The task type discriminator, matched against a manifest task's
     * {@code type} field. Must be unique across all beans.
     */
    String type();

    /**
     * Executes the task against the provisioned workspace described by
     * {@code ctx}. Must not throw for ordinary task failures — return a
     * {@link DamogranTaskResult#failure(String)} so the error rides the result
     * envelope (rendered like a notebook cell traceback). Throwing is reserved
     * for programming/wiring errors.
     */
    DamogranTaskResult execute(DamogranContext ctx, TaskSpec spec);
}
