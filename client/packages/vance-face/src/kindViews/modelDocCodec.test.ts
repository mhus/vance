import { describe, expect, it } from 'vitest';

import {
  MODEL_SOURCE_KIND,
  ModelDocParseError,
  applyForm,
  formFromDoc,
  parseModelDoc,
  preservedKeyCount,
  serializeModelDoc,
  type ModelDocForm,
} from './modelDocCodec';

/**
 * The contract behind `kind: vance-model`: the form owns its fields and
 * nothing else, `$meta.kind` is guaranteed on save, and — the addition the
 * catalog's deep-merge semantics force — an emptied field is a dropped key
 * that inherits, while the three known pricing keys merge into whatever
 * else the `pricing` map carries.
 */

function form(over: Partial<ModelDocForm> = {}): ModelDocForm {
  return {
    displayName: 'DeepSeek Chat',
    wireName: '',
    contextWindowTokens: '164000',
    defaultMaxOutputTokens: '32768',
    size: 'LARGE',
    capabilities: ['THINKING'],
    timeoutSeconds: '',
    pricingCurrency: 'USD',
    pricingInputPerMTok: '0.27',
    pricingOutputPerMTok: '1.1',
    ...over,
  };
}

describe('modelDocCodec', () => {
  it('parses a YAML mapping and rejects anything else', () => {
    expect(parseModelDoc('contextWindowTokens: 164000\n')).toEqual({ contextWindowTokens: 164000 });
    expect(parseModelDoc('')).toEqual({});
    expect(() => parseModelDoc('- a\n- b\n')).toThrow(ModelDocParseError);
    expect(() => parseModelDoc('a: [\n')).toThrow(ModelDocParseError);
  });

  it('reads numeric YAML scalars back as form strings', () => {
    // js-yaml parses bare digits as numbers; the form edits strings and the
    // serializer writes digits again — the round-trip must not corrupt them.
    const doc = parseModelDoc('contextWindowTokens: 164000\n');
    expect(formFromDoc(doc).contextWindowTokens).toBe('164000');
  });

  it('round-trips through serialize without losing values', () => {
    const doc = parseModelDoc('$meta:\n  kind: vance-model\ncontextWindowTokens: 8192\n');
    expect(parseModelDoc(serializeModelDoc(doc))).toEqual(doc);
  });

  it('keeps unknown keys and $meta through a form round-trip', () => {
    const doc = parseModelDoc(
      '$meta:\n  kind: vance-model\ncontextWindowTokens: 8192\n'
      + 'messageParser: quirks\nunsupportedParams: [logit_bias]\n',
    );

    const out = applyForm(doc, form({ contextWindowTokens: '64000' }));

    expect(out.messageParser).toBe('quirks');
    expect(out.unsupportedParams).toEqual(['logit_bias']);
    expect(out.$meta).toEqual({ kind: MODEL_SOURCE_KIND });
    expect(out.contextWindowTokens).toBe(64000);
  });

  it('drops emptied fields — an absent key inherits', () => {
    const doc = parseModelDoc('displayName: Old\ndefaultMaxOutputTokens: 4096\n');

    const out = applyForm(doc, form({ displayName: '', defaultMaxOutputTokens: '' }));

    expect(out.displayName).toBeUndefined();
    expect(out.defaultMaxOutputTokens).toBeUndefined();
  });

  it('drops capabilities when none is checked', () => {
    const doc = parseModelDoc('capabilities: [VISION, TELEPATHY]\n');

    const out = applyForm(doc, form({ capabilities: [] }));

    expect(out.capabilities).toBeUndefined();
  });

  it('merges the owned pricing keys and preserves unknown ones', () => {
    const doc = parseModelDoc('pricing:\n  currency: EUR\n  costPerImage: 0.04\n');

    const out = applyForm(doc, form({ pricingCurrency: 'USD', pricingInputPerMTok: '0.3', pricingOutputPerMTok: '' }));

    expect(out.pricing).toEqual({ currency: 'USD', inputPerMTok: 0.3, costPerImage: 0.04 });
  });

  it('drops the pricing key entirely when nothing priced remains', () => {
    const doc = parseModelDoc('pricing:\n  currency: USD\n');

    const out = applyForm(doc, form({ pricingCurrency: '', pricingInputPerMTok: '', pricingOutputPerMTok: '' }));

    expect(out.pricing).toBeUndefined();
  });

  it('writes numeric owned fields as YAML numbers, not digit strings', () => {
    const doc = parseModelDoc('contextWindowTokens: 8192\n');

    const out = applyForm(doc, form({ contextWindowTokens: '131072', pricingInputPerMTok: '0.15' }));

    // The catalog (and the kind handler's type check) read a quoted digit
    // string as a string and silently default away.
    expect(out.contextWindowTokens).toBe(131072);
    expect(out.pricing).toMatchObject({ inputPerMTok: 0.15 });
  });

  it('keeps an unparsable numeric field as written instead of inventing a number', () => {
    const doc = parseModelDoc('contextWindowTokens: 8192\n');

    const out = applyForm(doc, form({ contextWindowTokens: 'lots' }));

    expect(out.contextWindowTokens).toBe('lots');
  });

  it('guarantees $meta.kind even when the document never had a $meta block', () => {
    const doc = parseModelDoc('contextWindowTokens: 8192\n');

    const out = applyForm(doc, form());

    expect(out.$meta).toEqual({ kind: MODEL_SOURCE_KIND });
    expect(Object.keys(out)[0]).toBe('$meta');
  });

  it('counts only keys the form does not own, including pricing extras', () => {
    const doc = parseModelDoc(
      '$meta:\n  kind: vance-model\nmessageParser: quirks\n'
      + 'pricing:\n  currency: USD\n  costPerImage: 0.04\n',
    );

    // messageParser + costPerImage: the pricing block is owned, but its
    // unknown key is not rendered.
    expect(preservedKeyCount(doc)).toBe(2);
  });
});
