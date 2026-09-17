import { describe, it, expect } from 'vitest';

import { resolveRenderer, hasRenderer, kindLabel, kindIcon } from './registry';
import { parseFenceLang } from './parseFenceLang';

/**
 * The alias exists because the failure mode is silent: an LLM emitting a
 * ```mermaid fence (GitHub convention, and what its training data says)
 * used to get a plain <pre> code block instead of a rendered diagram —
 * no error, no hint, just "the diagrams don't show". These pin the
 * routing contract end to end: fence language → parseFenceLang →
 * registry lookup, for both the canonical kind and the alias.
 */
describe('kind alias routing', () => {
  it('routes a bare mermaid fence to the diagram renderer', () => {
    const { kind } = parseFenceLang('mermaid');

    expect(kind).toBe('mermaid');
    expect(hasRenderer(kind)).toBe(true);
    expect(resolveRenderer(kind, 'inline')?.label).toBe('Diagram');
  });

  it('resolves mermaid to the same renderer as the diagram kind', () => {
    expect(resolveRenderer('mermaid', 'embedded')).toBe(resolveRenderer('diagram', 'embedded'));
  });

  it('labels and icons the alias with the canonical kind metadata', () => {
    expect(kindLabel('mermaid')).toBe('Diagram');
    expect(kindIcon('mermaid')).toBe('📈');
  });

  it('is case-insensitive like the kind lookup itself', () => {
    expect(hasRenderer('Mermaid')).toBe(true);
    expect(resolveRenderer('MERMAID', 'inline')?.label).toBe('Diagram');
  });

  it('still falls back for genuinely unknown kinds', () => {
    expect(hasRenderer('d2')).toBe(false);
    expect(resolveRenderer('d2', 'inline')).toBeNull();
    expect(kindLabel('d2')).toBe('d2');
  });

  it('keeps fence meta parsing intact for the alias', () => {
    const { kind, meta } = parseFenceLang('mermaid theme=forest,look=handDrawn');

    expect(kind).toBe('mermaid');
    expect(meta).toEqual({ theme: 'forest', look: 'handDrawn' });
  });
});
