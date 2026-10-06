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
import { parseSchedulerDoc, serializeSchedulerDoc, type SchedulerDoc } from '@/kindViews/schedulerFormCodec';
import {
  ResearchSourceParseError,
  parseResearchSourceDoc,
  serializeResearchSourceDoc,
  type ResearchSourceDoc,
} from '@/kindViews/researchSourceCodec';
import { researchSourceSettingsProvider } from '@/kindViews/researchSourceSettingsProvider';
import {
  MountSourceParseError,
  parseMountSourceDoc,
  serializeMountSourceDoc,
  type MountSourceDoc,
} from '@/kindViews/mountSourceCodec';
import { mountSourceSettingsProvider } from '@/kindViews/mountSourceSettingsProvider';
import {
  ModelDocParseError,
  parseModelDoc,
  serializeModelDoc,
  type ModelDoc,
} from '@/kindViews/modelDocCodec';
import { modelCatalogSettingsProvider } from '@/kindViews/modelCatalogSettingsProvider';
import {
  ProviderDocParseError,
  parseProviderDoc,
  serializeProviderDoc,
  type ProviderDoc,
} from '@/kindViews/providerDocCodec';
import { recipeSettingsProvider } from '@/kindViews/recipeSettingsProvider';
import { chatThemeSettingsProvider, reportThemeSettingsProvider } from '@/kindViews/themeSettingsProvider';
import { isAgeDocument } from '@vance/age';

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
      () => import('@/components/MarkdownDocumentPreview.vue'),
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

  // ── Ursa scheduler: form view over the definition ────────────────
  // Model = the whole YAML map (schedulerFormCodec): the form owns the
  // fields it renders and every other key ($meta, params, tags, lockMode,
  // …) survives each round-trip. A cron the form cannot express degrades
  // to the raw-expression input instead of misrepresenting it.
  registerKind<SchedulerDoc>({
    id: 'vance-scheduler',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-scheduler',
    parse: parseSchedulerDoc,
    serialize: serializeSchedulerDoc,
    tabLabelKey: 'documents.schedulerView.tabLabel',
    view: defineAsyncComponent(
      () => import('@/kindViews/SchedulerFormView.vue'),
    ),
  });
  // ── Research source: form view over a source-config document ────
  // Model = the whole YAML map (researchSourceCodec): the form owns
  // protocol/baseUrl/apiKey/enabled plus the two per-protocol extras,
  // and every other key ($meta, readerIdentity, unknown protocol
  // fields) survives each round-trip. A protocol id this build does not
  // know degrades to a raw input instead of being snapped to a known
  // one — the form never misrepresents a source. `$meta.kind` is
  // guaranteed on save, so sources written before the kind existed
  // route here as well.
  registerKind<ResearchSourceDoc>({
    id: 'vance-research-source',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-research-source',
    parse: parseResearchSourceDoc,
    serialize: serializeResearchSourceDoc,
    isParseError: (e) => e instanceof ResearchSourceParseError,
    tabLabelKey: 'documents.researchSourceView.tabLabel',
    view: defineAsyncComponent(
      () => import('@/kindViews/ResearchSourceFormView.vue'),
    ),
    settingsProvider: researchSourceSettingsProvider,
  });
  // ── Mount source: Jaglan mount definition (settings area) ─────
  // One mounted external tree: _vance/config/mounts/<id>.yaml, server
  // truth SourceConfigLoader + JaglanSourceFactory. Same whole-map
  // contract as the research form — the mount codec owns its fields,
  // everything else passes through; $meta.kind guaranteed on save.
  // ── Model catalog: operator-managed model + provider documents ──
  // _vance/model/<provider>/<slug>.yaml + _provider.yaml sidecars, server
  // truth ModelCatalog (deep-merge per field, location-based — no kind
  // filter in the settings listing, the provider assigns the view kind by
  // filename). Partial documents are the point: an unset key inherits.
  registerKind<ModelDoc>({
    id: 'vance-model',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-model',
    parse: parseModelDoc,
    serialize: serializeModelDoc,
    isParseError: (e) => e instanceof ModelDocParseError,
    tabLabelKey: 'documents.modelDocView.tabLabel',
    view: defineAsyncComponent(
      () => import('@/kindViews/ModelDocFormView.vue'),
    ),
    settingsProvider: modelCatalogSettingsProvider,
  });
  // Provider sidecar (_provider.yaml): the document that makes a provider
  // instance usable — wire protocol + credential shape. Reached through the
  // same settings area (rows carry this kindId), never a kind filter of its
  // own.
  registerKind<ProviderDoc>({
    id: 'vance-model-provider',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-model-provider',
    parse: parseProviderDoc,
    serialize: serializeProviderDoc,
    isParseError: (e) => e instanceof ProviderDocParseError,
    tabLabelKey: 'documents.providerDocView.tabLabel',
    view: defineAsyncComponent(
      () => import('@/kindViews/ProviderDocFormView.vue'),
    ),
  });
  // ── Recipe: named configuration bundle (settings area, raw editor) ──
  // _vance/recipes/<name>.yaml, server truth RecipeLoader. Deliberately no
  // view and no codec: recipe YAMLs are the most complex configuration
  // documents there are, so the settings host falls back to its raw YAML
  // editor with save — inventory, create, delete, edit in place. The
  // listing is flat and location-based: what the loader resolves by name,
  // not the Slart working trees below it.
  registerKind({
    id: 'vance-recipe',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-recipe',
    tabLabelKey: 'documents.recipeView.tabLabel',
    settingsProvider: recipeSettingsProvider,
  });
  // ── CSS themes: chat transcript + PDF report (settings areas, raw CSS) ──
  // One named .css per theme, flat folder, [a-z0-9-]+ names, first-match
  // cascade — identical mechanics, different vocabulary (chat-themes.md /
  // report-themes.md). Like recipes: no view and no codec, the settings
  // host falls back to its CodeEditor (CSS highlighting via mime type).
  // No server KindHandler — both pipelines are fail-open by design, there
  // is no finding a validator could report the runtime would not absorb.
  registerKind({
    id: 'vance-chat-theme',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-chat-theme',
    tabLabelKey: 'documents.chatThemeView.tabLabel',
    settingsProvider: chatThemeSettingsProvider,
  });
  registerKind({
    id: 'vance-report-theme',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-report-theme',
    tabLabelKey: 'documents.reportThemeView.tabLabel',
    settingsProvider: reportThemeSettingsProvider,
  });
  registerKind<MountSourceDoc>({
    id: 'vance-mount-source',
    matches: (kind) => (kind ?? '').toLowerCase() === 'vance-mount-source',
    parse: parseMountSourceDoc,
    serialize: serializeMountSourceDoc,
    isParseError: (e) => e instanceof MountSourceParseError,
    tabLabelKey: 'documents.mountSourceView.tabLabel',
    view: defineAsyncComponent(
      () => import('@/kindViews/MountSourceFormView.vue'),
    ),
    settingsProvider: mountSourceSettingsProvider,
  });
  // ── Age: locked-state view for encrypted documents ────────────
  // An age-encrypted document renders here while no imported key fits
  // (AgeDocumentView = explanation + unlock form + cipher preview). Once
  // the cortex store's transform decrypts (cortex/ageDocument.ts), the
  // tab's kind/mime flip to the *inner* document and the normal inner
  // binding takes over — this entry only ever sees the locked shape.
  // Read-only by design: no parse/serialize, the body is ciphertext.
  // Embeds (chat, canvas nodes) get the same view in its card mode.
  registerKind({
    id: 'age',
    matches: (kind, mime) => isAgeDocument(kind, mime),
    view: defineAsyncComponent(
      () => import('@/kindViews/AgeDocumentView.vue'),
    ),
  });

  // ── QR Code: payload rendered as a scannable symbol ───────────
  // Body = payload (URL or any text) plus optional flat render
  // options — the identity codec keeps the Edit toggle a raw
  // CodeEditor (write the URL, see the symbol), QrCodeView parses
  // the front-matter / YAML / JSON options itself (qrcodeCodec.ts).
  // Text mimes only: a qrcode-typed binary makes no sense, and the
  // sniffing parser needs a textual body.
  registerKind<string>({
    id: 'qrcode',
    matches: (kind, mime) => {
      if ((kind ?? '').toLowerCase() !== 'qrcode') return false;
      const m = (mime ?? '').toLowerCase();
      return m === ''
        || m === 'text/markdown'
        || m === 'text/x-markdown'
        || m === 'text/plain'
        || m === 'application/json'
        || m === 'application/yaml'
        || m === 'application/x-yaml'
        || m === 'text/yaml'
        || m === 'text/x-yaml';
    },
    parse: (body) => body,
    serialize: (doc) => doc,
    view: defineAsyncComponent(
      () => import('@/kindViews/QrCodeView.vue'),
    ),
  });
}
