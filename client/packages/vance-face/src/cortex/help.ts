import type { CortexDocument } from './types';
import { resolveBinding } from './docTypeRegistry';
import { resolveRunAdapter } from './runners/runnerRegistry';

/**
 * Default Cortex help — covers the generic UX (tabs, View/Edit toggle,
 * chat binding, auto-save, Run button when present). Used when no
 * binding-specific help is mapped or the mapped file is missing.
 */
export const DEFAULT_HELP_PATH = 'cortex.md';

/**
 * Stable name → help-file mapping. Keys are {@code DocTypeBinding.id}
 * values for hand-rolled bindings (e.g. {@code 'list'}, {@code 'sheet'})
 * or {@code 'kind-registry:<id>'} for addon-contributed kinds. Adding
 * a new help file is a one-line entry here; the file itself lands in
 * {@code vance-brain/.../help/{lang}/<value>}.
 */
const BINDING_HELP: Record<string, string> = {
  tree: 'doc-kind-tree.md',
  list: 'doc-kind-list.md',
  checklist: 'doc-kind-checklist.md',
  records: 'doc-kind-records.md',
  chart: 'doc-kind-chart.md',
  sheet: 'doc-kind-sheet.md',
  graph: 'doc-kind-graph.md',
  mindmap: 'doc-kind-mindmap.md',
  slides: 'doc-kind-slides.md',
  diagram: 'doc-kind-diagram.md',
  image: 'doc-kind-image.md',
  // Kind-registry contributions follow the convention
  // 'kind-registry:<id>' → 'doc-kind-<id>.md'; resolution below builds
  // the path on the fly so addons don't need a registry entry here.
};

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
export function resolveHelpPath(doc: CortexDocument | null): string {
  if (!doc) return DEFAULT_HELP_PATH;

  const adapter = resolveRunAdapter(doc);
  if (adapter?.id === 'js') return 'script-cortex.md';
  if (adapter?.id === 'py') return 'python-cortex.md';

  const binding = resolveBinding(doc);
  if (binding.mode === 'kind-registry' && binding.kindEntry) {
    return `doc-kind-${binding.kindEntry.id}.md`;
  }
  const mapped = BINDING_HELP[binding.id];
  if (mapped) return mapped;

  return DEFAULT_HELP_PATH;
}
