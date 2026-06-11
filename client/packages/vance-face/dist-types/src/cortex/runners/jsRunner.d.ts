import type { RunAdapter } from './types';
/**
 * JS-execution adapter: drives the brain's {@code scripts/execute}
 * endpoint, subscribes to {@code script-execution-*} push events,
 * and falls back to short-interval polling when WS subscription
 * fails (e.g. the worker terminated before subscribe landed).
 *
 * <p>Logic mirrors {@code src/scripts/components/ExecutionDialog.vue}
 * but exposes it as a reactive handle the shell can drive without a
 * modal — the run lives inline below the editor.
 */
export declare const jsRunner: RunAdapter;
//# sourceMappingURL=jsRunner.d.ts.map