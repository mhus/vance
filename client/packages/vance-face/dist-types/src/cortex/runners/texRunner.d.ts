import type { RunAdapter } from './types';
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
 * <p>The result carries {@code { pdfPath }} on success so the shell
 * can render an "Open PDF" button that opens the freshly imported PDF
 * as a new tab.
 */
export declare const texRunner: RunAdapter;
//# sourceMappingURL=texRunner.d.ts.map