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
}

declare global {
  // eslint-disable-next-line no-var
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
