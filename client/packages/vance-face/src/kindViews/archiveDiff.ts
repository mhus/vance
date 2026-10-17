import { hasAgeExtension, isAgeDocument } from '@vance/age';
import type { DocumentArchiveSummary, DocumentDto } from '@vance/generated';
import { isTextualMime } from '@/composables/useDocumentArchives';
import type { DiffBlock, DiffRow } from '@/util/lineDiff';

/**
 * The version-compare logic of {@code DocumentArchives} and
 * {@code DocumentVersionDiff}, kept out of the components so it can be
 * tested without mounting them.
 */

/** One opened comparison: archived body (old) against the current content (new). */
export interface ArchiveDiff {
  archive: DocumentArchiveSummary;
  oldText: string;
  newText: string;
}

/** Where the two bodies of a comparison come from. */
export interface ArchiveDiffSources {
  loadText: (documentId: string, archiveId: string) => Promise<string | null>;
  loadLiveText: (documentId: string) => Promise<string | null>;
}

/**
 * Whether {@code archive} can be compared line by line. Never for an
 * age-encrypted document: the server holds ciphertext only, so either side
 * of the comparison would be armor, not text. The age markers are checked
 * on the document, its path and the archived version, and {@code encrypted}
 * covers a host whose document carries the decrypted (inner) kind and mime.
 */
export function canDiffArchive(
  archive: DocumentArchiveSummary,
  document: Pick<DocumentDto, 'kind' | 'mimeType' | 'path'> | null,
  encrypted: boolean,
): boolean {
  if (encrypted) return false;
  if (document && (isAgeDocument(document.kind, document.mimeType) || hasAgeExtension(document.path))) {
    return false;
  }
  if (isAgeDocument(archive.kind, archive.mimeType) || hasAgeExtension(archive.path)) return false;
  return isTextualMime(archive.mimeType ?? document?.mimeType);
}

/**
 * Loads both bodies of a comparison. {@code currentText} — the editor's
 * body, unsaved edits included — wins over the stored live body. Resolves
 * to {@code null} when a body could not be loaded or when {@code isCurrent}
 * says the panel moved on to another document while the requests ran: a
 * late answer must not open a dialog for the previous document.
 */
export async function loadArchiveDiff(
  sources: ArchiveDiffSources,
  documentId: string,
  archive: DocumentArchiveSummary,
  currentText: string | undefined,
  isCurrent: () => boolean,
): Promise<ArchiveDiff | null> {
  const [oldText, newText] = await Promise.all([
    sources.loadText(documentId, archive.id),
    currentText !== undefined ? Promise.resolve(currentText) : sources.loadLiveText(documentId),
  ]);
  if (!isCurrent() || oldText === null || newText === null) return null;
  return { archive, oldText, newText };
}

/**
 * Above this many lines (old and new together) the dialog does not diff at
 * all: the line diff runs synchronously on the UI thread.
 */
export const MAX_DIFF_LINES = 20_000;

/** Rows the dialog renders at first, and per "show more" click. */
export const DIFF_ROW_PAGE = 1_000;

/** Number of lines in {@code text} — what {@link MAX_DIFF_LINES} counts. */
export function lineCount(text: string): number {
  if (text === '') return 0;
  let n = 1;
  for (let i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) n++;
  return text.endsWith('\n') ? n - 1 : n;
}

/** One rendered line of the diff table: a diff row or a collapsed gap. */
export type DiffDisplayLine =
  | { key: string; type: 'row'; row: DiffRow }
  | { key: string; type: 'gap'; block: number; count: number };

/**
 * The diff blocks as table lines: a gap the user has not expanded is one
 * line, every other block contributes its rows. The dialog renders a
 * prefix of this list, so a huge diff does not put every row in the DOM.
 */
export function diffDisplayLines(blocks: DiffBlock[], expanded: ReadonlySet<number>): DiffDisplayLine[] {
  const out: DiffDisplayLine[] = [];
  blocks.forEach((block, bi) => {
    if (block.type === 'gap' && !expanded.has(bi)) {
      out.push({ key: `g${bi}`, type: 'gap', block: bi, count: block.rows.length });
      return;
    }
    block.rows.forEach((row, ri) => out.push({ key: `${bi}-${ri}`, type: 'row', row }));
  });
  return out;
}
