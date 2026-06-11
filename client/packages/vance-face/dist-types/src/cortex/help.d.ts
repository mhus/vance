import type { CortexDocument } from './types';
/**
 * Default Cortex help — covers the generic UX (tabs, View/Edit toggle,
 * chat binding, auto-save, Run button when present). Used when no
 * binding-specific help is mapped or the mapped file is missing.
 */
export declare const DEFAULT_HELP_PATH = "cortex.md";
/**
 * Resolve which help file to show for the given document. Lookup
 * order:
 *
 *  1. If a {@link resolveRunAdapter run adapter} matches the doc, its
 *     own help wins — the user just ran (or is about to run) a script;
 *     ScriptCortex's help is the most useful thing to surface.
 *  2. Hand-rolled binding mapping (see {@link BINDING_HELP}).
 *  3. Kind-registry binding → {@code doc-kind-<kindId>.md} (convention).
 *  4. {@link DEFAULT_HELP_PATH} as the final fallback.
 *
 * The returned path is just the {@code path} segment of
 * {@code help/{lang}/{path}} — the brain prepends the language.
 */
export declare function resolveHelpPath(doc: CortexDocument | null): string;
//# sourceMappingURL=help.d.ts.map