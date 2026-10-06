import { describe, expect, it } from 'vitest';

import {
  PROVIDER_SOURCE_KIND,
  ProviderDocParseError,
  applyForm,
  parseProviderDoc,
  preservedKeyCount,
  serializeProviderDoc,
  type ProviderDocForm,
} from './providerDocCodec';

/**
 * The contract behind `kind: vance-model-provider` (the `_provider.yaml`
 * sidecar): the form owns its fields and nothing else, `$meta.kind` is
 * guaranteed on save, an emptied field is a dropped key that inherits.
 */

function form(over: Partial<ProviderDocForm> = {}): ProviderDocForm {
  return {
    displayName: 'My Gateway',
    wireType: 'openai',
    authType: 'api-key',
    baseUrl: 'https://gw.example.test/v1',
    ...over,
  };
}

describe('providerDocCodec', () => {
  it('parses a YAML mapping and rejects anything else', () => {
    expect(parseProviderDoc('wireType: openai\n')).toEqual({ wireType: 'openai' });
    expect(parseProviderDoc('')).toEqual({});
    expect(() => parseProviderDoc('- a\n- b\n')).toThrow(ProviderDocParseError);
    expect(() => parseProviderDoc('a: [\n')).toThrow(ProviderDocParseError);
  });

  it('round-trips through serialize without losing values', () => {
    const doc = parseProviderDoc('$meta:\n  kind: vance-model-provider\nwireType: ollama\n');
    expect(parseProviderDoc(serializeProviderDoc(doc))).toEqual(doc);
  });

  it('keeps unknown keys and $meta through a form round-trip', () => {
    const doc = parseProviderDoc(
      '$meta:\n  kind: vance-model-provider\nwireType: openai\nsomeFutureField: 7\n',
    );

    const out = applyForm(doc, form({ baseUrl: 'https://other.test' }));

    expect(out.someFutureField).toBe(7);
    expect(out.$meta).toEqual({ kind: PROVIDER_SOURCE_KIND });
    expect(out.baseUrl).toBe('https://other.test');
  });

  it('drops emptied fields — an absent key inherits', () => {
    const doc = parseProviderDoc('displayName: Old\nbaseUrl: https://x.test\n');

    const out = applyForm(doc, form({ displayName: '', baseUrl: '' }));

    expect(out.displayName).toBeUndefined();
    expect(out.baseUrl).toBeUndefined();
  });

  it('guarantees $meta.kind even when the sidecar never had a $meta block', () => {
    const doc = parseProviderDoc('wireType: openai\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ kind: PROVIDER_SOURCE_KIND });
    expect(Object.keys(out)[0]).toBe('$meta');
  });

  it('counts only keys the form does not own', () => {
    const doc = parseProviderDoc(
      '$meta:\n  kind: vance-model-provider\nwireType: openai\nauthHeaderName: x-api-key\n',
    );

    expect(preservedKeyCount(doc)).toBe(1);
  });
});
