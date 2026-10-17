import { describe, expect, it } from 'vitest';
import { nextNotePath, quickMarkdownPath } from './quickMarkdownPath';

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
