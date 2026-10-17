import DiffMatchPatch from 'diff-match-patch';

/**
 * Line-based text diff for the version-compare dialog. Built on
 * diff-match-patch's line mode (each line is hashed to one char, the
 * char strings are diffed, then expanded back), so the result reads
 * like a classic unified diff rather than a character soup.
 */

export type DiffRowKind = 'same' | 'add' | 'del';

export interface DiffRow {
  kind: DiffRowKind;
  /** 1-based line number in the old text; {@code null} for added lines. */
  oldNo: number | null;
  /** 1-based line number in the new text; {@code null} for removed lines. */
  newNo: number | null;
  text: string;
}

/**
 * Display unit after context collapsing: either a run of rows to render
 * or a gap standing for {@code rows.length} unchanged lines that are
 * hidden until the user expands them.
 */
export type DiffBlock =
  | { type: 'rows'; rows: DiffRow[] }
  | { type: 'gap'; rows: DiffRow[] };

export interface DiffStats {
  added: number;
  removed: number;
}

function splitLines(chunk: string): string[] {
  const lines = chunk.split('\n');
  // A chunk of whole lines ends with '\n' — the split leaves an empty tail.
  if (lines.length > 0 && lines[lines.length - 1] === '') lines.pop();
  return lines;
}

function normalize(text: string): string {
  const unix = text.replace(/\r\n?/g, '\n');
  // A missing trailing newline would otherwise make the last line differ
  // from its unchanged twin ("foo" vs "foo\n") and show up as a change.
  return unix === '' || unix.endsWith('\n') ? unix : `${unix}\n`;
}

/** Diffs {@code oldText} against {@code newText} line by line. */
export function computeLineDiff(oldText: string, newText: string): DiffRow[] {
  const dmp = new DiffMatchPatch.diff_match_patch();
  const encoded = dmp.diff_linesToChars_(normalize(oldText), normalize(newText));
  const diffs = dmp.diff_main(encoded.chars1, encoded.chars2, false);
  dmp.diff_charsToLines_(diffs, encoded.lineArray);

  const rows: DiffRow[] = [];
  let oldNo = 1;
  let newNo = 1;
  for (const [op, chunk] of diffs) {
    for (const text of splitLines(chunk)) {
      if (op === DiffMatchPatch.DIFF_EQUAL) {
        rows.push({ kind: 'same', oldNo: oldNo++, newNo: newNo++, text });
      } else if (op === DiffMatchPatch.DIFF_DELETE) {
        rows.push({ kind: 'del', oldNo: oldNo++, newNo: null, text });
      } else {
        rows.push({ kind: 'add', oldNo: null, newNo: newNo++, text });
      }
    }
  }
  return rows;
}

export function diffStats(rows: DiffRow[]): DiffStats {
  let added = 0;
  let removed = 0;
  for (const row of rows) {
    if (row.kind === 'add') added++;
    else if (row.kind === 'del') removed++;
  }
  return { added, removed };
}

/**
 * Groups rows into visible runs and collapsible gaps: every change keeps
 * {@code context} unchanged lines around it, longer unchanged stretches
 * become a gap. A gap shorter than two lines is not worth a click and is
 * shown inline.
 */
export function collapseContext(rows: DiffRow[], context = 3): DiffBlock[] {
  const visible = rows.map(() => false);
  rows.forEach((row, i) => {
    if (row.kind === 'same') return;
    const from = Math.max(0, i - context);
    const to = Math.min(rows.length - 1, i + context);
    for (let j = from; j <= to; j++) visible[j] = true;
  });

  const blocks: DiffBlock[] = [];
  let i = 0;
  while (i < rows.length) {
    const show = visible[i];
    let end = i;
    while (end < rows.length && visible[end] === show) end++;
    const run = rows.slice(i, end);
    const type = show || run.length < 2 ? 'rows' : 'gap';
    const last = blocks[blocks.length - 1];
    if (type === 'rows' && last?.type === 'rows') last.rows.push(...run);
    else blocks.push({ type, rows: run });
    i = end;
  }
  return blocks;
}
