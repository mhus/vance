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

describe('serialize/parse — S6 round-trip hardening', () => {
  it('a code block containing a triple-backtick line survives (length-aware fence)', () => {
    const doc: WorkPageDocument = {
      title: null, description: null, icon: null, cover: null,
      blocks: [{ kind: 'code', lang: 'md', code: 'a fence:\n```\ninner\n```\ndone' }],
    };
    const back = parseDocument(serializeDocument(doc));
    const code = back.blocks.find((b) => b.kind === 'code') as { code: string } | undefined;
    // The inner ``` line must not have closed the outer block.
    expect(code?.code).toContain('```\ninner\n```');
    expect(back.blocks.length).toBe(1);
  });

  it('a title beginning with a YAML indicator char round-trips', () => {
    for (const title of ['@team sync', '!Important', '- draft', '> note', '`code`', '*star']) {
      const doc: WorkPageDocument = {
        title, description: null, icon: null, cover: null,
        blocks: [{ kind: 'paragraph', text: 'x' }],
      };
      const back = parseDocument(serializeDocument(doc));
      expect(back.title).toBe(title);
    }
  });

  it('table cells containing | and newlines round-trip', () => {
    const doc: WorkPageDocument = {
      title: null, description: null, icon: null, cover: null,
      blocks: [{ kind: 'table', headers: ['a|b', 'c'], rows: [['x\ny', 'p|q']] }],
    };
    const back = parseDocument(serializeDocument(doc));
    const t = back.blocks.find((b) => b.kind === 'table') as
      { headers: string[]; rows: string[][] } | undefined;
    expect(t?.headers).toEqual(['a|b', 'c']);
    expect(t?.rows[0]).toEqual(['x\ny', 'p|q']);
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
