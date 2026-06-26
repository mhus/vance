import type { RunAdapter } from '@vance/runner-registry';
/**
 * Python-execution adapter for the Cortex Run button. Talks to the
 * brain's REST surface ({@code POST /python/execute},
 * {@code GET /python/executions/{id}},
 * {@code POST /python/executions/{id}/cancel}) and surfaces a live
 * snapshot through polling — the ExecManager pipeline this runs on
 * doesn't emit WS push events (unlike ScriptCortex's JS path), so
 * the runner just re-fetches the status every ~1.5s and replaces the
 * log buffer with the latest stdout / stderr snapshot.
 *
 * <p>Args translate to shell-args (List&lt;String&gt;), unlike the JS
 * adapter's free-form arg-map. The user types a JSON-array string in
 * the toolbar (parsed by DocumentTabShell.onRun); we accept the array
 * here verbatim.
 */
export declare const pythonRunner: RunAdapter;
//# sourceMappingURL=pythonRunner.d.ts.map