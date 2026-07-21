import { describe, it, expect } from 'vitest';
import {
  parseInline,
  serializeInline,
  parseInlineToProseMirror,
  serializeProseMirrorInline,
} from './inline';

/**
 * The inline codec is byte-stable for the syntax it supports (marks, links,
 * `[[wikilinks]]`, escapes). These round-trip tests guard against silent
 * regressions in the hand-written scanner — the kind a typecheck can't catch.
 */
describe('inline codec — segment-level round-trip', () => {
  const cases = [
    'plain text with no marks',
    '**bold** and *italic* and `code` and ~~strike~~',
    'a [link](https://example.com) inline',
    'A plain [[Foo]] link.',
    'Aliased [[Deploy Guide|the guide]] here.',
    'Two [[A]] and [[B|bee]] in one line.',
    'Mixed **bold** and [[Wiki]] and `code`.',
    'Space path [[eng/Deploy]] link.',
  ];
  for (const c of cases) {
    it(`serializeInline(parseInline(x)) === x — ${JSON.stringify(c)}`, () => {
      expect(serializeInline(parseInline(c))).toBe(c);
    });
  }

  // Escapes are intentionally NOT byte-stable: the parser consumes the
  // backslash (`\*` → a literal `*`), and the serializer does not re-escape.
  // The lone `*` re-parses as plain text (no matching `*`), so the codec is
  // stable from the second pass on — just not equal to the escaped source.
  it('escape is consumed on parse, then stable (fixpoint, not byte-stable)', () => {
    const once = serializeInline(parseInline('escaped \\* not italic'));
    expect(once).toBe('escaped * not italic');
    expect(serializeInline(parseInline(once))).toBe(once);
  });
});

describe('inline codec — ProseMirror-node round-trip', () => {
  const cases = [
    'A plain [[Foo]] link.',
    'Aliased [[Deploy Guide|the guide]] here.',
    'Mixed **bold** and [[Wiki]] and `code`.',
    'a [link](https://example.com) inline',
  ];
  for (const c of cases) {
    it(`serialize(parse) === x — ${JSON.stringify(c)}`, () => {
      expect(serializeProseMirrorInline(parseInlineToProseMirror(c))).toBe(c);
    });
  }
});

describe('wikiLink node extraction', () => {
  it('labeled [[target|label]] → wikiLink node with target + label', () => {
    const nodes = parseInlineToProseMirror('see [[Deploy Guide|the guide]] now');
    const wl = nodes.find((n) => n.type === 'wikiLink');
    expect(wl).toBeDefined();
    expect(wl?.attrs?.target).toBe('Deploy Guide');
    expect(wl?.attrs?.label).toBe('the guide');
  });

  it('bare [[Foo]] → label equals target', () => {
    const nodes = parseInlineToProseMirror('x [[Foo]] y');
    const wl = nodes.find((n) => n.type === 'wikiLink');
    expect(wl?.attrs?.target).toBe('Foo');
    expect(wl?.attrs?.label).toBe('Foo');
  });

  it('space-qualified [[eng/Deploy]] keeps the full target', () => {
    const nodes = parseInlineToProseMirror('[[eng/Deploy]]');
    const wl = nodes.find((n) => n.type === 'wikiLink');
    expect(wl?.attrs?.target).toBe('eng/Deploy');
  });

  it('empty [[]] is not treated as a wikilink', () => {
    const nodes = parseInlineToProseMirror('a [[]] b');
    expect(nodes.some((n) => n.type === 'wikiLink')).toBe(false);
  });
});
