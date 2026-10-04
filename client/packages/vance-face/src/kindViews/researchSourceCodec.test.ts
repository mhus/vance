import { describe, expect, it } from 'vitest';

import {
  RESEARCH_SOURCE_KIND,
  ResearchSourceParseError,
  applyForm,
  formFromDoc,
  parseResearchSourceDoc,
  preservedKeyCount,
  serializeResearchSourceDoc,
  showsCapsTtl,
  showsContactEmail,
  type ResearchSourceForm,
} from './researchSourceCodec';

/**
 * The contract behind `kind: vance-research-source`: the form owns its fields
 * and nothing else, `$meta.kind` is guaranteed on save, and a protocol the
 * form does not know is kept verbatim rather than snapped to a known one.
 */

function form(over: Partial<ResearchSourceForm> = {}): ResearchSourceForm {
  return {
    protocol: 'serper',
    baseUrl: 'https://google.serper.dev',
    apiKey: '{noop}sk-123',
    enabled: true,
    contactEmail: '',
    capsTtlSeconds: '',
    ...over,
  };
}

describe('researchSourceCodec', () => {
  it('parses a YAML mapping and rejects anything else', () => {
    expect(parseResearchSourceDoc('protocol: serper\n')).toEqual({ protocol: 'serper' });
    expect(parseResearchSourceDoc('')).toEqual({});
    expect(() => parseResearchSourceDoc('- a\n- b\n')).toThrow(ResearchSourceParseError);
    expect(() => parseResearchSourceDoc('a: [\n')).toThrow(ResearchSourceParseError);
  });

  it('round-trips through serialize without losing values', () => {
    const doc = parseResearchSourceDoc('$meta:\n  kind: vance-research-source\nprotocol: serper\n');
    expect(parseResearchSourceDoc(serializeResearchSourceDoc(doc))).toEqual(doc);
  });

  it('keeps unknown keys and $meta through a form round-trip', () => {
    const doc = parseResearchSourceDoc(
      '$meta:\n  kind: vance-research-source\nprotocol: serper\nreaderIdentity: pseudonym\nsomeFutureField: 7\n',
    );

    const out = applyForm(doc, form({ baseUrl: 'https://example.test' }));

    expect(out.readerIdentity).toBe('pseudonym');
    expect(out.someFutureField).toBe(7);
    expect(out.$meta).toEqual({ kind: RESEARCH_SOURCE_KIND });
    expect(out.baseUrl).toBe('https://example.test');
  });

  it('guarantees $meta.kind even when the document never had a $meta block', () => {
    const doc = parseResearchSourceDoc('protocol: serper\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ kind: RESEARCH_SOURCE_KIND });
    // …and it comes first, like the templates write it.
    expect(Object.keys(out)[0]).toBe('$meta');
  });

  it('keeps other $meta entries when stamping the kind', () => {
    const doc = parseResearchSourceDoc('$meta:\n  title: Serper\nprotocol: serper\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ title: 'Serper', kind: RESEARCH_SOURCE_KIND });
  });

  it('keeps a protocol id the form does not know', () => {
    const doc = parseResearchSourceDoc('protocol: my-own-thing\n');

    expect(formFromDoc(doc).protocol).toBe('my-own-thing');
    expect(applyForm(doc, form({ protocol: 'my-own-thing' })).protocol).toBe('my-own-thing');
  });

  it('drops empty optional keys instead of writing blanks', () => {
    const doc = parseResearchSourceDoc('protocol: serper\napiKey: "{noop}old"\ncontactEmail: a@b.test\n');

    const out = applyForm(doc, form({ apiKey: '', contactEmail: '' }));

    expect(out.apiKey).toBeUndefined();
    expect(out.contactEmail).toBeUndefined();
  });

  it('writes enabled explicitly in both directions', () => {
    const doc = parseResearchSourceDoc('protocol: serper\n');

    expect(applyForm(doc, form({ enabled: false })).enabled).toBe(false);
    expect(applyForm(doc, form({ enabled: true })).enabled).toBe(true);
  });

  it('counts only keys the form does not own', () => {
    const doc = parseResearchSourceDoc(
      '$meta:\n  kind: vance-research-source\nprotocol: serper\nreaderIdentity: none\nother: 1\n',
    );

    expect(preservedKeyCount(doc)).toBe(2);
  });

  it('shows the per-protocol extras only for their protocols', () => {
    expect(showsContactEmail('openalex')).toBe(true);
    expect(showsContactEmail('pubmed')).toBe(true);
    expect(showsContactEmail('serper')).toBe(false);
    expect(showsCapsTtl('ode')).toBe(true);
    expect(showsCapsTtl('wikipedia')).toBe(false);
  });
});