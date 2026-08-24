import { describe, it, expect } from 'vitest';
import { hasServerTag, isJsDocument } from './jsDocument';

describe('isJsDocument', () => {
  it('accepts a js mime regardless of the path', () => {
    expect(isJsDocument({ id: '1', path: 'notes/thing', mimeType: 'text/javascript' }))
      .toBe(true);
  });

  it('falls back to the extension when no mime is set', () => {
    expect(isJsDocument({ id: '1', path: 'scripts/sum.mjs', mimeType: null }))
      .toBe(true);
  });

  it('rejects a non-js document', () => {
    expect(isJsDocument({ id: '1', path: 'notes/readme.md', mimeType: 'text/markdown' }))
      .toBe(false);
  });
});

describe('hasServerTag', () => {
  it('detects the bare flag in the header block', () => {
    expect(hasServerTag('/**\n * @description x\n * @server\n */\nfoo();'))
      .toBe(true);
  });

  it('ignores a value the author added anyway', () => {
    expect(hasServerTag('/**\n * @server true\n */\n')).toBe(true);
  });

  it('is false for a frontend script whose header omits the tag', () => {
    expect(hasServerTag('/**\n * @description x\n * @timeout 5s\n */\nfoo();'))
      .toBe(false);
  });

  it('does not match a longer tag that merely starts with server', () => {
    expect(hasServerTag('/**\n * @serverless\n */\n')).toBe(false);
  });

  it('only reads the first block — a later mention must not arm Run', () => {
    // Same rule the brain's ScriptHeaderParser applies: everything
    // after the first block is regular documentation.
    expect(hasServerTag('/**\n * @description x\n */\nfoo();\n/**\n * @server\n */'))
      .toBe(false);
  });

  it('treats a not-yet-loaded body as undeclared', () => {
    expect(hasServerTag(null)).toBe(false);
    expect(hasServerTag('')).toBe(false);
  });

  it('is false when the file has no header block at all', () => {
    expect(hasServerTag('// @server\nfoo();')).toBe(false);
  });
});
