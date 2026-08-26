/**
 * In-memory representation of a document open in Cortex. Mirrors the
 * server's DocumentDto, plus a {@code dirty} flag tracking unsaved
 * edits in the current tab.
 */
export interface CortexDocument {
  id: string;
  path: string;
  name: string;
  title?: string | null;
  mimeType?: string | null;
  /**
   * Document kind from {@code DocumentDto.kind} — e.g. "list",
   * "checklist", "tree", "records", "sheet", "chart", "graph", "image".
   * The DocumentTabShell uses {@code (kind, mimeType)} pairs to pick a
   * typed-model renderer; absent / unknown kinds fall back to the
   * Code-Tab.
   */
  kind?: string | null;
  inlineText: string;
  /**
   * Read parameters this tab was opened with — the query of a
   * parameterised view (`from=2026-02-01&to=2026-03-31` on a mounted
   * document), without the leading `?`. Undefined is the ordinary case:
   * the document as it stands.
   *
   * <p>It belongs to the <b>tab</b>, not to the document: the row is the
   * same one either way, only the answer differs (see
   * `specification/public/jaglan-system.md` §5a). Consequences: the
   * content fetch carries it, the tab is read-only while it is set
   * (writing back would target the base document), and it travels in the
   * URL like every other piece of Cortex view state.
   */
  viewQuery?: string;
  /** True when {@link inlineText} has been edited since load/save. */
  dirty: boolean;
  /**
   * Snapshot of {@link inlineText} as of the last server-acknowledged
   * sync — set on every load and after every successful save. Drives the
   * dirty check the live-change reaction layer consults to decide
   * whether a {@code documents.changed} event can be absorbed silently
   * or needs a conflict banner; Phase B will also use it as the
   * {@code text1} side of a 3-way merge.
   */
  baselineInlineText: string;
  /**
   * Last hash the deep-validator reviewed. Populated from
   * {@code DocumentDto.lastDeepReviewedHash}; the cached-warnings
   * panel uses it to decide whether the cached findings still apply
   * to the current body.
   */
  lastDeepReviewedHash?: string | null;
  /**
   * JSON-encoded {@code ScriptDeepWarning[]} from the most recent
   * deep-validate run, mirrored from the server DTO so the dialog
   * can show "cached findings" without an extra fetch.
   */
  lastDeepReviewWarningsJson?: string | null;

  /**
   * Accent color from the restricted 12-value palette; {@code null}
   * means no color set. Mirrors {@code DocumentDto.color} /
   * {@code DocumentSummary.color} so the file tree and the properties
   * panel render the same accent without an extra fetch.
   */
  color?: import('@vance/generated').AccentColor | null;

  // ─── Mirrored DocumentDto metadata ──────────────────────────────
  // Populated on the full {@code openFile} fetch (not on the list
  // summary) so the Properties panel can render them without an
  // extra round-trip. Stay {@code null}/undefined on documents that
  // are still represented only by their {@link DocumentSummary}.
  tags?: string[] | null;
  size?: number | null;
  createdAtMs?: number | null;
  createdBy?: string | null;
  summary?: string | null;
  summarizedAtMs?: number | null;
  autoSummary?: boolean | null;
  summaryDirty?: boolean | null;
  ragEnabled?: boolean | null;
  /**
   * Parsed front-matter (or upload-inferred) headers from the backend's
   * {@code DocumentDocument.headers}. Read-only metadata surfaced in
   * the Properties panel. Empty for documents without front matter.
   */
  headers?: Record<string, string>;
  /**
   * Sticky-notes attached to this document, keyed by note id. Loaded
   * once together with the document; mutated through the dedicated
   * {@code /notes} REST endpoints via the {@code useDocumentNotes}
   * composable. Empty when the document has no notes.
   */
  notes?: Record<string, import('@vance/generated').DocumentNoteDto>;

  /**
   * Soft document-lock — writer roles blocked from mutating the
   * document. Mirrors {@code DocumentDto.lockedFor}; mutated through
   * the dedicated {@code PATCH /lock} endpoint via
   * {@code cortexStore.updateLock}. Empty when no lock is set.
   */
  lockedFor?: import('@vance/generated').WriterRole[];
}

/**
 * Folder-tree node, assembled from the folder listings loaded so far
 * (see {@code folderTree.ts}). One node per folder the session has heard
 * of — which is not the same as one per folder that has been read.
 */
export interface FolderNode {
  /** Full path prefix, e.g. {@code "utils/math"} */
  path: string;
  /** Last path segment, e.g. {@code "math"} */
  name: string;
  /** Direct sub-folders — empty until this folder has been loaded. */
  children: FolderNode[];
  /** Files directly inside this folder — likewise. */
  files: CortexDocument[];
  /**
   * Whether this folder's listing has been fetched. A node can exist
   * without it: the parent's listing names the folder, and only opening it
   * asks the server what is inside.
   */
  loaded: boolean;
  /** A fetch for this folder is in flight. */
  loading: boolean;
  /** Why the last fetch failed, or {@code null}. */
  error: string | null;
  /**
   * Files the server counted but did not send (one page per folder). Shown
   * as a row rather than dropped: a silently truncated folder is what made
   * documents look deleted.
   */
  moreFiles: number;
}

/**
 * What an app remote reports as its own selection — a card, a hit, a row,
 * anything that is not a character range in a document.
 *
 * <p>Two halves with different lifetimes, and that is the point. {@code
 * selection} is a phrase for the current turn's prompt and is dropped with
 * it, because carrying it forward would claim the reader is still looking
 * at something they left. {@code ref} is what gets persisted on the message
 * the reader sends, so the sentence "tell me more about the selected entry"
 * still has a referent once the entry has scrolled away.
 *
 * <p>{@code appDocId} scopes both to the app tab that reported them.
 */
export interface AppSelectionReport {
  appDocId: string;
  selection: string;
  /**
   * Label plus at least one address — {@code vanceUri} points back into
   * this installation ({@code vance:/…?entry=…}), {@code url} at the thing's
   * origin. Apps that have neither leave it out and nothing is persisted.
   * Shape mirrors {@code SelectionReference} in {@code @vance/generated};
   * declared structurally here so app remotes need no generated import.
   */
  ref?: { label: string; vanceUri?: string; url?: string } | null;
}
