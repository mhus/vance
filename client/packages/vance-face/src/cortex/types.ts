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
   * Sticky-notes attached to this document, keyed by note id. Loaded
   * once together with the document; mutated through the dedicated
   * {@code /notes} REST endpoints via the {@code useDocumentNotes}
   * composable. Empty when the document has no notes.
   */
  notes?: Record<string, import('@vance/generated').DocumentNoteDto>;
}

/**
 * Folder-tree node, built client-side by aggregating the path prefixes
 * of every document in the project.
 */
export interface FolderNode {
  /** Full path prefix, e.g. {@code "utils/math"} */
  path: string;
  /** Last path segment, e.g. {@code "math"} */
  name: string;
  /** Direct sub-folders. */
  children: FolderNode[];
  /** Files directly inside this folder. */
  files: CortexDocument[];
}
