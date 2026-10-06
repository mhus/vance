import type { Component } from 'vue';

/**
 * One Kind entry — declarative description of a document Kind that
 * the host's DocumentApp dispatches to. Built-ins register at boot
 * (see vance-face/src/document/builtInKinds.ts); addons register
 * from their `./register` federation expose.
 *
 * The generic {@code TDoc} is the kind's typed document model. The
 * host treats it opaquely — codec + view components are written
 * against the same shape and are only ever paired with each other.
 */
export interface KindEntry<TDoc = unknown> {
  /**
   * Stable kind identifier, e.g. {@code "calendar"}. Matched against
   * the document's {@code kind} field. Registry key — registering a
   * second entry with the same id replaces the first (HMR-friendly).
   */
  id: string;

  /**
   * Returns {@code true} when this Kind handles a document with the
   * given {@code kind} metadata and MIME type. Both inputs are taken
   * straight off the {@code DocumentDto} so they may be {@code null}
   * / {@code undefined}. The host applies the predicate after a
   * built-in kind-routing pass, so a {@code false} return cleanly
   * falls back to the raw-text editor.
   */
  matches: (kind: string | null | undefined, mime: string | null | undefined) => boolean;

  /**
   * Vue component that renders the document. Receives {@code doc} as
   * a prop (typed as {@code TDoc}). The component is rendered via
   * Vue's {@code <component :is>}, so {@code defineAsyncComponent}
   * works for code-splitting.
   *
   * Optional for entries that only contribute a {@link codePreview}
   * (read-only preview for code-mode documents like Markdown / TeX).
   * When absent, {@code resolveBinding} skips the entry for the
   * kind-registry dispatch path — the document stays in the
   * catch-all 'code' binding and the shell checks
   * {@link codePreview} separately.
   */
  view?: Component;

  /**
   * Optional edit-mode component. Falls back to {@link view} when not
   * supplied (kinds where view IS the editor — Markdown for example).
   */
  editor?: Component;

  /**
   * Optional live-preview component for code-kind documents (e.g.
   * {@code .tex} files that want a KaTeX-rendered preview alongside the
   * raw CodeEditor). When set, the DocumentTabShell shows a View/Edit
   * toggle for documents in the catch-all {@code code} binding whose
   * MIME-type this Kind matches — just like Markdown does with
   * {@link MarkdownView}. The component receives {@code source: string}
   * as a prop.
   *
   * <p>Unlike {@link view}, this does NOT replace the CodeEditor as the
   * primary editor — it adds a toggle-able preview pane on top of it.
   * The CodeEditor remains the edit target; {@code codePreview} is the
   * read-only rendered view.
   */
  codePreview?: Component;

  /**
   * Codec: parse the on-disk inline body into the typed model. Called
   * by the host on every keystroke in the raw editor to surface live
   * parse errors above the view tab.
   *
   * Optional: kinds without a parse step (binary previews like PDF /
   * image) skip this and the host renders the view component without
   * a {@code doc} prop.
   */
  parse?: (body: string, mime: string) => TDoc;

  /**
   * Codec: serialise the typed model back to an on-disk inline body.
   * Required for kinds with an editor; read-only views (Calendar v1)
   * may omit it.
   */
  serialize?: (doc: TDoc, mime: string) => string;

  /**
   * Type-guard the codec uses to flag its own parse errors. The host
   * uses this to surface a kind-specific message instead of a generic
   * one when {@link parse} throws. Other exceptions (unrelated bugs)
   * bubble up untouched. Optional — when missing, every throw from
   * {@link parse} is treated as a parse error.
   */
  isParseError?: (e: unknown) => boolean;

  /**
   * i18n key for the editor tab label (e.g. {@code documents.detail.tabCalendar}).
   * Used by DocumentApp's tab strip — optional, falls back to {@link id}.
   */
  tabLabelKey?: string;

  /**
   * i18n key for the parse-error banner (e.g. {@code documents.detail.calendarParseError}).
   * Used by DocumentApp when {@link parse} throws — optional, falls back to a
   * generic message.
   */
  parseErrorKey?: string;

  /**
   * Present iff this kind's documents are configuration surfaces —
   * one document of the kind configures one instance of something (a
   * research source, a feed endpoint, a mount, …). The Settings panel
   * lists these documents per scope next to the Setting Forms; clicking
   * a row opens the kind's normal editor — the provider only supplies
   * the inventory, never a renderer.
   *
   * <p>Registered here (and not in a parallel registry) because addons
   * already register their kinds through this store — a settings
   * contribution rides along with the kind entry, same
   * {@code globalThis} mechanics.
   */
  settingsProvider?: SettingsProvider;
}

/**
 * The scope a settings listing is asked for — the three persisted setting
 * layers in their wire form (see {@code settings-system.md} §3). The
 * {@code projectId} is what the panel passes on to the document and
 * setting-form endpoints: {@code _tenant} for the tenant layer,
 * {@code _user_<login>} for the user layer.
 */
export interface SettingsScope {
  kind: 'tenant' | 'user' | 'project';
  /** Project name backing the scope ({@code _tenant}, {@code _user_<login>} or a plain project). */
  projectId: string;
  /** The login, set for {@code kind: 'user'}. */
  login?: string;
}

/** One row of a settings-area entry inventory. */
export interface SettingsDocRow {
  /** Stable entry name (e.g. file stem) — row key. */
  name: string;
  /** Display title. */
  title: string;
  /** Short description line, optional. */
  description?: string;
  /** Kind id the row belongs to (the registering entry's {@link KindEntry.id}). */
  kindId: string;
  /** Document id of the entry — loaded and edited inline by the Settings page. */
  documentId: string;
  /** Project the document lives in (may differ from the scope project when the provider lists inherited rows). */
  projectId: string;
  /** Document path, optional — location-aware views use it for warnings. */
  path?: string;
}

/**
 * Contribution of a settings-doc kind: an area in the Settings page's
 * "Bereiche" tab — its entry inventory per scope, plus (optional) entry
 * creation. Opening and editing an entry is the host's job: it loads the
 * document by {@link SettingsDocRow.documentId} and renders the kind's own
 * view, so the provider supplies only the inventory, never a renderer.
 *
 * <p>Deleting an entry is host-generic (document delete by id); a provider
 * that needs a different flow can hook the registry again later.
 */
export interface SettingsProvider {
  /**
   * i18n key for the area label in the "Bereiche" tab (e.g.
   * {@code settings.areas.research}). Resolved with the host's merged
   * message catalog, so addon-registered keys work too.
   */
  titleKey: string;
  /** Inventory for one scope. Failures should resolve to an empty list (the host shows its own error surface). */
  list: (scope: SettingsScope) => Promise<SettingsDocRow[]>;
  /**
   * Creates a new entry with the given name and returns its row (the
   * host opens it right away). Omitted ⇒ the area shows no add button.
   */
  create?: (scope: SettingsScope, name: string) => Promise<SettingsDocRow>;
}

declare global {
  var __VANCE_KIND_REGISTRY__: Map<string, KindEntry> | undefined;
}

function store(): Map<string, KindEntry> {
  let s = globalThis.__VANCE_KIND_REGISTRY__;
  if (!s) {
    s = new Map<string, KindEntry>();
    globalThis.__VANCE_KIND_REGISTRY__ = s;
  }
  return s;
}

/**
 * Register a Kind entry. Idempotent — registering the same id again
 * replaces the previous entry (handy for HMR + addon re-load).
 */
export function registerKind<TDoc = unknown>(entry: KindEntry<TDoc>): void {
  store().set(entry.id, entry as KindEntry);
}

/**
 * Resolve a Kind by its stable id.
 */
export function resolveKind<TDoc = unknown>(id: string): KindEntry<TDoc> | undefined {
  return store().get(id) as KindEntry<TDoc> | undefined;
}

/**
 * First Kind whose matcher accepts the given {@code kind} + MIME pair.
 * Iteration order is insertion order — host built-ins register before
 * addons, so a built-in wins ties.
 */
export function resolveKindFor(
  kind: string | null | undefined,
  mime: string | null | undefined,
): KindEntry | undefined {
  for (const entry of store().values()) {
    if (entry.matches(kind, mime)) return entry;
  }
  return undefined;
}

/**
 * Snapshot of all currently-registered kinds in insertion order.
 */
export function listKinds(): KindEntry[] {
  return [...store().values()];
}

/**
 * Snapshot of all kinds that contribute a settings surface, in
 * insertion order. The Settings panel calls each entry's
 * {@link SettingsProvider.list} for the selected scope.
 */
export function listSettingsKinds(): KindEntry[] {
  return listKinds().filter((k) => k.settingsProvider != null);
}
