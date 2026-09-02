import { describe, expect, it } from 'vitest';
import {
  canTranslateSelection,
  isTranslatableDocument,
  looksTruncated,
  stripWrappingFence,
  suggestTranslatedName,
} from './translate';

describe('isTranslatableDocument', () => {
  it('accepts markdown and plain text by mime', () => {
    expect(isTranslatableDocument({ path: 'documents/a.md', mimeType: 'text/markdown' })).toBe(true);
    expect(isTranslatableDocument({ path: 'documents/a.txt', mimeType: 'text/plain' })).toBe(true);
  });

  it('accepts a charset parameter on the mime', () => {
    expect(isTranslatableDocument({
      path: 'documents/a.md',
      mimeType: 'text/markdown; charset=utf-8',
    })).toBe(true);
  });

  it('falls back to the extension only when the server reports no mime', () => {
    expect(isTranslatableDocument({ path: 'documents/notes.md', mimeType: null })).toBe(true);
    expect(isTranslatableDocument({ path: 'documents/config.yaml', mimeType: null })).toBe(false);
  });

  it('rejects text that is not prose', () => {
    // These are text, and this entry writes a *file*: a translated YAML is a
    // YAML its own parser can no longer read.
    expect(isTranslatableDocument({ path: 'a.yaml', mimeType: 'application/yaml' })).toBe(false);
    expect(isTranslatableDocument({ path: 'a.js', mimeType: 'text/javascript' })).toBe(false);
  });

  it('rejects a structured kind even when its body is markdown', () => {
    // `list`, `records` and friends are YAML inside a fence: translating them
    // would rewrite the keys along with the values.
    expect(isTranslatableDocument({
      path: 'documents/todo.md',
      mimeType: 'text/markdown',
      kind: 'list',
    })).toBe(false);
    expect(isTranslatableDocument({
      path: 'documents/page.md',
      mimeType: 'text/markdown',
      kind: 'workpage',
    })).toBe(false);
  });

  it('accepts the kinds that are still prose', () => {
    for (const kind of [null, '', 'text', 'markdown']) {
      expect(isTranslatableDocument({ path: 'a.md', mimeType: 'text/markdown', kind })).toBe(true);
    }
  });
});

describe('canTranslateSelection', () => {
  it('accepts every document whose text can be marked', () => {
    // Nothing is written back, so the narrow prose rule does not apply: a
    // comment in a script and a `description:` in a config are fair game.
    expect(canTranslateSelection({ path: 'a.yaml', mimeType: 'application/yaml' })).toBe(true);
    expect(canTranslateSelection({ path: 'a.json', mimeType: 'application/json' })).toBe(true);
    expect(canTranslateSelection({ path: 'a.py', mimeType: 'text/x-python' })).toBe(true);
    expect(canTranslateSelection({ path: 'a.md', mimeType: 'text/markdown' })).toBe(true);
  });

  it('accepts a structured kind, which the whole-document entry refuses', () => {
    expect(canTranslateSelection({ path: 'todo.md', mimeType: 'text/markdown' })).toBe(true);
  });

  it('rejects binaries, which have no text surface at all', () => {
    expect(canTranslateSelection({ path: 'a.png', mimeType: 'image/png' })).toBe(false);
    expect(canTranslateSelection({ path: 'a.pdf', mimeType: 'application/pdf' })).toBe(false);
  });
});

describe('suggestTranslatedName', () => {
  it('pushes the language in front of the extension', () => {
    expect(suggestTranslatedName('manual.md', 'de')).toBe('manual.de.md');
  });

  it('appends when there is no extension', () => {
    expect(suggestTranslatedName('README', 'fr')).toBe('README.fr');
  });

  it('treats a leading dot as the name, not an extension', () => {
    expect(suggestTranslatedName('.notes', 'de')).toBe('.notes.de');
  });

  it('cannot stack suffixes, because it always reads the source name', () => {
    // The dialog re-derives from the source on every language change; this is
    // what keeps `manual.de.en.md` impossible.
    expect(suggestTranslatedName(suggestTranslatedName('manual.md', 'de'), 'en'))
      .toBe('manual.de.en.md');
    expect(suggestTranslatedName('manual.md', 'en')).toBe('manual.en.md');
  });

  it('leaves the name alone when the code is unusable', () => {
    expect(suggestTranslatedName('manual.md', '  ')).toBe('manual.md');
  });
});

describe('stripWrappingFence', () => {
  it('removes a fence that wraps the whole reply', () => {
    expect(stripWrappingFence('```markdown\n# Titel\n\nText\n```'))
      .toBe('# Titel\n\nText');
  });

  it('leaves a document that legitimately contains a fence', () => {
    const doc = '# Titel\n\n```js\nconst a = 1;\n```\n\nSchluss';
    expect(stripWrappingFence(doc)).toBe(doc);
  });

  it('leaves a document whose fences are unbalanced', () => {
    // Four fence lines: two code blocks, not one wrapper.
    const doc = '```js\na\n```\n```js\nb\n```';
    expect(stripWrappingFence(doc)).toBe(doc);
  });
});

describe('looksTruncated', () => {
  it('flags a result far shorter than its source', () => {
    expect(looksTruncated('x'.repeat(4000), 'x'.repeat(500))).toBe(true);
  });

  it('leaves normal length variation alone', () => {
    // German runs longer than English; Chinese much shorter than either. The
    // threshold has to sit below the shrinking cases that are still complete.
    expect(looksTruncated('x'.repeat(4000), 'x'.repeat(2400))).toBe(false);
  });

  it('says nothing about short sources', () => {
    expect(looksTruncated('Hallo Welt', '你好')).toBe(false);
  });
});
