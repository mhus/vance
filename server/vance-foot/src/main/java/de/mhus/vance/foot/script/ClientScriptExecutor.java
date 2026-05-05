package de.mhus.vance.foot.script;

/**
 * Single entry point for foot-side JavaScript execution. Implementations
 * build a fresh {@link org.graalvm.polyglot.Context} per run, inject the
 * {@code client} host object backed by the foot's local
 * {@link de.mhus.vance.foot.tools.ClientToolService}, evaluate the
 * source, and close the context.
 */
public interface ClientScriptExecutor {

    /**
     * Runs {@code request.code()}. Throws
     * {@link ClientScriptExecutionException} on any failure.
     */
    ClientScriptResult run(ClientScriptRequest request);
}
