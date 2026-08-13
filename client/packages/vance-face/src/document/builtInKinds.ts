/**
 * Bootstrap-time registration of host-built-in document Kinds.
 *
 * The runtime {@code @vance/kind-registry} is the single place
 * Cortex's {@code docTypeRegistry.resolveBinding} looks up a Kind's
 * view + codec for any registry-driven branch. Built-ins land here;
 * addons populate the same registry from their {@code ./register}
 * federation expose. When a Kind moves from built-in to addon, the
 * call below moves verbatim into the addon's register.ts and this
 * file shrinks by one entry — {@code docTypeRegistry} stays unchanged.
 *
 * Only Kinds that {@code docTypeRegistry} dispatches *via the
 * registry* land here. Most built-ins still use the static
 * {@code if/else} dispatch and don't need a registration — they'll
 * migrate as additional addons get carved out.
 */

import { defineAsyncComponent } from 'vue';
import { registerKind } from '@vance/kind-registry';

export function registerBuiltInKinds(): void {
  // ── Markdown: code-preview toggle ──────────────────────────────
  // Markdown files resolve to the catch-all 'code' binding in
  // docTypeRegistry (resolveBinding skips Kind entries without a
  // view). The codePreview field gives the shell a rendered
  // MarkdownView for the View/Edit toggle — raw CodeEditor in
  // 'edit', rendered HTML in 'view'. No view/codec needed.
  registerKind({
    id: 'markdown',
    // Markdown built-in is the *fallback* for plain Markdown files —
    // not a generic catch-all for every `text/markdown` document. If a
    // document has an explicit `kind` (e.g. `canvas`, registered by an
    // addon), that addon's view should win. We treat a missing / blank
    // / generic `markdown` kind as "plain Markdown" and only match
    // then. Without this guard, registerKind insertion order makes
    // markdown swallow every canvas / addon kind that happens to live
    // on a `text/markdown` mime.
    matches: (kind, mime) => {
      if (mime !== 'text/markdown') return false;
      const k = (kind ?? '').toLowerCase();
      return k === '' || k === 'markdown' || k === 'text';
    },
    codePreview: defineAsyncComponent(
      () => import('@/components/MarkdownView.vue'),
    ),
  });

  // ── TeX: KaTeX code-preview toggle ─────────────────────────────
  // Same pattern as Markdown: .tex files resolve to the catch-all
  // 'code' binding, but get a View/Edit toggle via codePreview —
  // KaTeX-rendered formula preview in 'view', raw CodeEditor with
  // stex highlighting in 'edit'. The "Generate PDF" run adapter
  // handles full LaTeX compilation independently.
  registerKind({
    id: 'tex',
    matches: (_kind, mime) =>
      mime === 'text/x-tex' || mime === 'application/x-tex',
    codePreview: defineAsyncComponent(
      () => import('@/cortex/components/TexPreview.vue'),
    ),
  });

  // ── Formula: KaTeX+mhchem code-preview toggle ──────────────────
  // `.formula` files get the same View/Edit toggle as `.tex`, but
  // use FormulaView (with mhchem support) instead of TexPreview.
  // Kind-based match so `kind: formula` documents resolve here even
  // without a specific MIME type.
  registerKind({
    id: 'formula',
    matches: (kind, mime) =>
      (kind ?? '').toLowerCase() === 'formula' ||
      mime === 'text/x-formula',
    codePreview: defineAsyncComponent(
      () => import('@/kindViews/FormulaView.vue'),
    ),
  });

  // ── Compose (Damogran): kind-registry view with a raw-YAML Edit tab ──
  // A `compose` document is YAML, identified by *kind* (not mime), so it
  // needs the kind-registry `view` path (kind-aware) rather than a
  // mime-based codePreview. parse/serialize are identity(string): the
  // shell's Edit toggle gives a raw YAML CodeEditor (edit + save), and the
  // View tab (ComposeView) runs the compose and renders its outputs.
  registerKind<string>({
    id: 'compose',
    matches: (kind) => (kind ?? '').toLowerCase() === 'compose',
    parse: (body) => body,
    serialize: (doc) => doc,
    view: defineAsyncComponent(
      () => import('@/cortex/components/ComposeView.vue'),
    ),
  });

  // ── Magrathea workflow: state machine drawn as a flow ──────────
  // Same identity-codec shape as compose — the Edit tab stays a raw
  // YAML CodeEditor (the definition is the artefact, and it is what
  // the server parses), the View tab renders the state graph.
  // Matched by kind alone: a workflow document is one wherever it
  // lives, not only under `_vance/workflows/` (spec §2.5).
  registerKind<string>({
    id: 'vance-workflow',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-workflow',
    parse: (body) => body,
    serialize: (doc) => doc,
    tabLabelKey: 'documents.workflowView.tabLabel',
    view: defineAsyncComponent(
      () => import('@/kindViews/WorkflowFlowView.vue'),
    ),
  });
}
