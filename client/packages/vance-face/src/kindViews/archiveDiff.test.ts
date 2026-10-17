import { describe, expect, it, vi } from 'vitest';
import type { DocumentArchiveSummary } from '@vance/generated';
import { collapseContext, computeLineDiff } from '@/util/lineDiff';
import {
  canDiffArchive,
  diffDisplayLines,
  lineCount,
  loadArchiveDiff,
  type ArchiveDiffSources,
} from './archiveDiff';

vi.mock('@vance/shared', () => ({ brainFetch: vi.fn(), brainFetchText: vi.fn() }));

function archive(over: Partial<DocumentArchiveSummary> = {}): DocumentArchiveSummary {
  return {
    id: 'a1',
    lineageId: 'l1',
    path: 'notes/plan.md',
    name: 'plan.md',
    mimeType: 'text/markdown',
    size: 10,
    tags: [],
    inline: true,
    archivedAtMs: 0,
    ...over,
  };
}

const PLAIN = { kind: undefined, mimeType: 'text/markdown', path: 'notes/plan.md' };

describe('canDiffArchive', () => {
  it('offers a textual version of a plain document', () => {
    expect(canDiffArchive(archive(), PLAIN, false)).toBe(true);
  });

  it('refuses a binary version', () => {
    expect(canDiffArchive(archive({ mimeType: 'image/png' }), PLAIN, false)).toBe(false);
  });

  it('falls back to the document mime when the version has none', () => {
    expect(canDiffArchive(archive({ mimeType: undefined }), PLAIN, false)).toBe(true);
  });

  it('refuses when the host says the document is encrypted', () => {
    expect(canDiffArchive(archive(), PLAIN, true)).toBe(false);
  });

  it('refuses an age document by mime, kind or path', () => {
    expect(canDiffArchive(archive(), { ...PLAIN, mimeType: 'application/age+armored' }, false)).toBe(false);
    expect(canDiffArchive(archive(), { ...PLAIN, kind: 'age' }, false)).toBe(false);
    expect(canDiffArchive(archive(), { ...PLAIN, path: 'notes/plan.md.age' }, false)).toBe(false);
  });

  it('refuses an encrypted version of a document decrypted since', () => {
    expect(canDiffArchive(archive({ mimeType: 'application/age+armored' }), PLAIN, false)).toBe(false);
  });
});

describe('loadArchiveDiff', () => {
  function sources(oldText: string | null, liveText: string | null) {
    const src = {
      loadText: vi.fn(async (_documentId: string, _archiveId: string) => oldText),
      loadLiveText: vi.fn(async (_documentId: string) => liveText),
    };
    return src satisfies ArchiveDiffSources;
  }

  it('compares against the editor body when there is one', async () => {
    const src = sources('old', 'stored');
    const diff = await loadArchiveDiff(src, 'd1', archive(), 'edited', () => true);
    expect(diff).toEqual({ archive: archive(), oldText: 'old', newText: 'edited' });
    expect(src.loadText).toHaveBeenCalledWith('d1', 'a1');
    expect(src.loadLiveText).not.toHaveBeenCalled();
  });

  it('loads the stored body without an editor body', async () => {
    const src = sources('old', 'stored');
    const diff = await loadArchiveDiff(src, 'd1', archive(), undefined, () => true);
    expect(diff?.newText).toBe('stored');
    expect(src.loadLiveText).toHaveBeenCalledWith('d1');
  });

  it('opens nothing when a body could not be loaded', async () => {
    expect(await loadArchiveDiff(sources(null, 'x'), 'd1', archive(), undefined, () => true)).toBeNull();
    expect(await loadArchiveDiff(sources('x', null), 'd1', archive(), undefined, () => true)).toBeNull();
  });

  it('drops a late answer for a document the panel has left', async () => {
    let current = 'd1';
    const src = sources('old', 'stored');
    const pending = loadArchiveDiff(src, 'd1', archive(), undefined, () => current === 'd1');
    current = 'd2';
    expect(await pending).toBeNull();
  });
});

describe('lineCount', () => {
  it('counts lines with and without a trailing newline', () => {
    expect(lineCount('')).toBe(0);
    expect(lineCount('a')).toBe(1);
    expect(lineCount('a\n')).toBe(1);
    expect(lineCount('a\nb')).toBe(2);
    expect(lineCount('a\n\nb\n')).toBe(3);
  });
});

describe('diffDisplayLines', () => {
  const oldText = Array.from({ length: 20 }, (_, i) => `line ${i}`).join('\n');
  const newText = oldText.replace('line 10', 'line ten');
  const blocks = collapseContext(computeLineDiff(oldText, newText));

  it('shows a collapsed gap as one line', () => {
    const lines = diffDisplayLines(blocks, new Set());
    const gaps = lines.filter((l) => l.type === 'gap');
    expect(gaps.length).toBe(2);
    expect(lines.length).toBe(2 + 3 + 2 + 3);
  });

  it('shows the rows of an expanded gap', () => {
    const collapsed = diffDisplayLines(blocks, new Set());
    const firstGap = collapsed.find((l) => l.type === 'gap');
    if (firstGap?.type !== 'gap') throw new Error('expected a gap');
    const lines = diffDisplayLines(blocks, new Set([firstGap.block]));
    expect(lines.length).toBe(collapsed.length - 1 + firstGap.count);
    expect(new Set(lines.map((l) => l.key)).size).toBe(lines.length);
  });
});
