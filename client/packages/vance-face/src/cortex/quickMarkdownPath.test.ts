import { describe, expect, it, vi } from 'vitest';
import { createNextNote, nextNotePath, quickMarkdownPath } from './quickMarkdownPath';

describe('quickMarkdownPath', () => {
  it('joins folder and name with a single slash and appends .md', () => {
    expect(quickMarkdownPath('documents/notes', 'todo')).toBe('documents/notes/todo.md');
  });

  it('does not double the slash when the folder ends with one', () => {
    expect(quickMarkdownPath('documents/notes/', 'todo')).toBe('documents/notes/todo.md');
    expect(quickMarkdownPath('documents', '/todo')).toBe('documents/todo.md');
  });

  it('keeps an existing .md suffix', () => {
    expect(quickMarkdownPath('documents', 'todo.md')).toBe('documents/todo.md');
    expect(quickMarkdownPath('documents', 'Todo.MD')).toBe('documents/Todo.MD');
  });

  it('places the file at the project root for an empty folder', () => {
    expect(quickMarkdownPath('', 'todo')).toBe('todo.md');
    expect(quickMarkdownPath(' / ', 'todo')).toBe('todo.md');
  });

  it('returns null for a blank name', () => {
    expect(quickMarkdownPath('documents', '  ')).toBeNull();
  });

  it('returns null for a name that names a folder', () => {
    expect(quickMarkdownPath('documents', '/')).toBeNull();
    expect(quickMarkdownPath('documents', 'sub/')).toBeNull();
    expect(quickMarkdownPath('documents', 'sub//')).toBeNull();
  });

  it('returns null for a name that is only the suffix', () => {
    expect(quickMarkdownPath('documents', '.md')).toBeNull();
    expect(quickMarkdownPath('documents', 'sub/.MD')).toBeNull();
  });

  it('keeps a subfolder inside the name', () => {
    expect(quickMarkdownPath('documents', 'sub/todo')).toBe('documents/sub/todo.md');
  });
});

describe('nextNotePath', () => {
  it('starts at note1.md', () => {
    expect(nextNotePath('documents', () => false)).toEqual({ path: 'documents/note1.md', index: 1 });
  });

  it('skips taken indices', () => {
    const taken = new Set(['documents/note1.md', 'documents/note2.md']);
    expect(nextNotePath('documents', (p) => taken.has(p))).toEqual({ path: 'documents/note3.md', index: 3 });
  });

  it('continues from a given index', () => {
    expect(nextNotePath('', () => false, 5)).toEqual({ path: 'note5.md', index: 5 });
  });
});

describe('createNextNote', () => {
  class Conflict extends Error {}
  const isConflict = (e: unknown) => e instanceof Conflict;

  it('creates the first free note without retrying', async () => {
    const create = vi.fn().mockResolvedValue(undefined);
    await expect(createNextNote('documents', () => false, create, isConflict, 3)).resolves.toBe('documents/note1.md');
    expect(create).toHaveBeenCalledTimes(1);
  });

  it('steps to the next index on each conflict', async () => {
    const create = vi.fn()
      .mockRejectedValueOnce(new Conflict('409'))
      .mockRejectedValueOnce(new Conflict('409'))
      .mockResolvedValue(undefined);
    await expect(createNextNote('documents', () => false, create, isConflict, 5)).resolves.toBe('documents/note3.md');
    expect(create.mock.calls.map((c) => c[0])).toEqual([
      'documents/note1.md',
      'documents/note2.md',
      'documents/note3.md',
    ]);
  });

  it('skips paths the client knows to be taken when stepping on', async () => {
    const taken = new Set(['documents/note2.md']);
    const create = vi.fn().mockRejectedValueOnce(new Conflict('409')).mockResolvedValue(undefined);
    await expect(createNextNote('documents', (p) => taken.has(p), create, isConflict, 5)).resolves.toBe('documents/note3.md');
  });

  it('gives up after the conflict limit and rethrows the conflict', async () => {
    const create = vi.fn().mockRejectedValue(new Conflict('409'));
    await expect(createNextNote('', () => false, create, isConflict, 2)).rejects.toBeInstanceOf(Conflict);
    expect(create.mock.calls.map((c) => c[0])).toEqual(['note1.md', 'note2.md', 'note3.md']);
  });

  it('rethrows any other error at once', async () => {
    const create = vi.fn().mockRejectedValue(new Error('boom'));
    await expect(createNextNote('', () => false, create, isConflict, 5)).rejects.toThrow('boom');
    expect(create).toHaveBeenCalledTimes(1);
  });
});
