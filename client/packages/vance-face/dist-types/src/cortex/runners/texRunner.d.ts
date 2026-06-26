import type { RunAdapter } from '@vance/runner-registry';
/**
 * TeX compilation adapter: posts the compose path to the brain's
 * {@code tex/compile} endpoint, which runs latexmk (or the configured
 * rbehzadan executor) server-side and imports the resulting PDF as a
 * document. The call is synchronous — the backend blocks until the
 * compiler finishes (or times out).
 *
 * <p>Matches both {@code .tex} files and {@code tex-compose.yaml}
 * files. For {@code .tex} files, the adapter looks up a sibling
 * {@code tex-compose.yaml} in the same directory via the cortex store;
 * when none is found, the run fails with a hint to create one.
 *
 * <p>On success, the handle carries a {@code RunAction} "Open PDF"
 * that refreshes the file list and opens the freshly imported PDF as
 * a new tab — the shell renders it as a button in the log panel
 * without needing any TeX-specific knowledge.
 */
export declare const texRunner: RunAdapter;
//# sourceMappingURL=texRunner.d.ts.map