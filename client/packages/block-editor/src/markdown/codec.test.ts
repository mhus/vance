import { describe, it, expect } from 'vitest';
import { parse, parseDocument } from './parser';
import { serialize, serializeDocument } from './serializer';
import type { WorkPageDocument } from './blocks';

/**
 * Block-level Markdown codec. The parser output shape is asserted directly
 * (leniently, via matchObject — extra fields are fine); the round-trip is
 * checked as a fixpoint (serialize→parse is stable) so the tests don't hard-
 * code the exact canonical whitespace the serializer chooses.
 */
describe('parse — block structure', () => {
  it('headings carry level + text', () => {
    const blocks = parse('# Hello\n\nWorld');
    expect(blocks[0]).toMatchObject({ kind: 'heading', level: 1, text: 'Hello' });
    expect(blocks[1]).toMatchObject({ kind: 'paragraph', text: 'World' });
  });

  it('bullet list collects its items', () => {
    const blocks = parse('- one\n- two\n- three');
    expect(blocks[0]).toMatchObject({ kind: 'bullet-list', items: ['one', 'two', 'three'] });
  });

  it('todo items keep checked state', () => {
    const blocks = parse('- [ ] open\n- [x] done');
    expect(blocks[0]).toMatchObject({
      kind: 'todo',
      items: [
        { checked: false, text: 'open' },
        { checked: true, text: 'done' },
      ],
    });
  });

  it('fenced code keeps language + body', () => {
    const blocks = parse('```js\nconst x = 1;\n```');
    expect(blocks[0]).toMatchObject({ kind: 'code', lang: 'js' });
    expect((blocks[0] as { code: string }).code).toContain('const x = 1;');
  });

  it('a paragraph preserves inline wikilink syntax verbatim', () => {
    const blocks = parse('Go to [[Foo]] and [[eng/Deploy|the guide]] now');
    expect(blocks[0]).toMatchObject({
      kind: 'paragraph',
      text: 'Go to [[Foo]] and [[eng/Deploy|the guide]] now',
    });
  });
});

describe('serialize/parse — round-trip fixpoint', () => {
  it('a rich block list reaches a stable canonical form', () => {
    const md = [
      '# Title',
      '',
      'A paragraph with **bold**, `code` and a [[Wikilink]].',
      '',
      '- one',
      '- two',
      '',
      '> a quote',
    ].join('\n');
    const once = serialize(parse(md));
    const twice = serialize(parse(once));
    expect(twice).toBe(once);
    expect(once).toContain('[[Wikilink]]');
  });
});

describe('parseDocument/serializeDocument — front-matter + blocks', () => {
  it('title, icon and blocks survive a full document round-trip', () => {
    const doc: WorkPageDocument = {
      title: 'My Page',
      description: null,
      icon: '📚',
      cover: null,
      blocks: [
        { kind: 'heading', level: 1, text: 'Intro' },
        { kind: 'paragraph', text: 'See [[Foo]] and **bold**.' },
        { kind: 'bullet-list', items: ['alpha', 'beta'] },
      ],
    };
    const once = parseDocument(serializeDocument(doc));
    const twice = parseDocument(serializeDocument(once));

    // Codec reaches a fixpoint (no drift on repeated round-trips).
    expect(twice).toEqual(once);
    // Fields that matter survive.
    expect(once.title).toBe('My Page');
    expect(once.icon).toBe('📚');
    const para = once.blocks.find((b) => b.kind === 'paragraph') as { text: string } | undefined;
    expect(para?.text).toContain('[[Foo]]');
    expect(para?.text).toContain('**bold**');
  });
});
