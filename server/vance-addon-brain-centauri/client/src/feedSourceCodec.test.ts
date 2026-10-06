import { describe, expect, it } from 'vitest';

import {
  FEED_SOURCE_KIND,
  FeedSourceParseError,
  applyForm,
  formFromDoc,
  parseFeedSourceDoc,
  preservedKeyCount,
  serializeFeedSourceDoc,
  type FeedSourceForm,
} from './feedSourceCodec';

/**
 * The contract behind `kind: vance-feed-source`: the form owns its fields
 * and nothing else, `$meta.kind` is guaranteed on save, and a protocol the
 * form does not know is kept verbatim rather than snapped to a known one.
 */

function form(over: Partial<FeedSourceForm> = {}): FeedSourceForm {
  return {
    protocol: 'usgs',
    baseUrl: 'https://earthquake.usgs.gov',
    apiKey: '',
    enabled: true,
    ...over,
  };
}

describe('feedSourceCodec', () => {
  it('parses a YAML mapping and rejects anything else', () => {
    expect(parseFeedSourceDoc('protocol: usgs\n')).toEqual({ protocol: 'usgs' });
    expect(parseFeedSourceDoc('')).toEqual({});
    expect(() => parseFeedSourceDoc('- a\n- b\n')).toThrow(FeedSourceParseError);
    expect(() => parseFeedSourceDoc('a: [\n')).toThrow(FeedSourceParseError);
  });

  it('round-trips through serialize without losing values', () => {
    const doc = parseFeedSourceDoc('$meta:\n  kind: vance-feed-source\nprotocol: usgs\n');
    expect(parseFeedSourceDoc(serializeFeedSourceDoc(doc))).toEqual(doc);
  });

  it('keeps unknown keys and $meta through a form round-trip', () => {
    const doc = parseFeedSourceDoc(
      '$meta:\n  kind: vance-feed-source\nprotocol: usgs\nreaderIdentity: pseudonym\nsomeFutureField: 7\n',
    );

    const out = applyForm(doc, form({ baseUrl: 'https://example.test' }));

    expect(out.readerIdentity).toBe('pseudonym');
    expect(out.someFutureField).toBe(7);
    expect(out.$meta).toEqual({ kind: FEED_SOURCE_KIND });
    expect(out.baseUrl).toBe('https://example.test');
  });

  it('guarantees $meta.kind even when the document never had a $meta block', () => {
    const doc = parseFeedSourceDoc('protocol: usgs\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ kind: FEED_SOURCE_KIND });
    // …and it comes first, like the templates write it.
    expect(Object.keys(out)[0]).toBe('$meta');
  });

  it('keeps other $meta entries when stamping the kind', () => {
    const doc = parseFeedSourceDoc('$meta:\n  title: Quakes\nprotocol: usgs\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ title: 'Quakes', kind: FEED_SOURCE_KIND });
  });

  it('keeps a protocol id the form does not know', () => {
    const doc = parseFeedSourceDoc('protocol: mastodon\n');

    expect(formFromDoc(doc).protocol).toBe('mastodon');
    expect(applyForm(doc, form({ protocol: 'mastodon' })).protocol).toBe('mastodon');
  });

  it('drops empty optional keys instead of writing blanks', () => {
    const doc = parseFeedSourceDoc('protocol: usgs\napiKey: "{noop}old"\n');

    const out = applyForm(doc, form({ apiKey: '' }));

    expect(out.apiKey).toBeUndefined();
  });

  it('writes enabled explicitly in both directions', () => {
    const doc = parseFeedSourceDoc('protocol: usgs\n');

    expect(applyForm(doc, form({ enabled: false })).enabled).toBe(false);
    expect(applyForm(doc, form({ enabled: true })).enabled).toBe(true);
  });

  it('counts only keys the form does not own', () => {
    const doc = parseFeedSourceDoc(
      '$meta:\n  kind: vance-feed-source\nprotocol: usgs\nreaderIdentity: none\nother: 1\n',
    );

    expect(preservedKeyCount(doc)).toBe(2);
  });
});
