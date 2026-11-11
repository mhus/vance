import { describe, expect, it } from 'vitest';
import {
  FinanceCodecError,
  type FinanceTreeDoc,
  isFinanceMime,
  isFinanceParseError,
  parseFinance,
  serializeFinance,
} from './financeCodec';

/**
 * The client codec mirrors the server's `FinanceTreeCodec`. The Cortex shell
 * now owns this kind's saves, so a divergence in grammar or compactness shows
 * up as a body that the server then re-reads differently — pin both.
 */

const YAML = 'application/yaml';
const JSON_MIME = 'application/json';

const SAMPLE = `# vance finance-tree v1 · amounts are per record; a node's sign flips its whole subtree; a disabled node counts as 0
# agent: run manual_read('finance-tree') before interpreting · computed values under $computed
$meta:
  kind: finance-tree
version: 1
title: Plan
root:
  name: projekt
  title: Projekt
  children:
    - name: einnahmen
      title: Einnahmen
      icon: "💰"
    - name: ausgaben
      sign: -1
      disabled: true
      values:
        - value: 800
          period:
            count: 1
            unit: month
          validFrom: 2026-01-01
        - value: 50.5
          mode: one_time
          validFrom: 2026-03-01
          interest:
            rate: 3
            period:
              count: 1
              unit: year
            basis: pro_mille
            compound: true
`;

function sample(): FinanceTreeDoc {
  return parseFinance(SAMPLE, YAML);
}

describe('parseFinance', () => {
  it('reads the model and re-applies defaults', () => {
    const doc = sample();
    expect(doc.version).toBe(1);
    expect(doc.title).toBe('Plan');
    const [income, expenses] = doc.root!.children;
    expect(income.sign).toBe(1);
    expect(income.disabled).toBe(false);
    expect(income.values).toEqual([]);
    expect(income.icon).toBe('💰');
    expect(expenses.sign).toBe(-1);
    expect(expenses.disabled).toBe(true);
  });

  it('keeps ISO dates as strings and reads value records', () => {
    const [recurring, oneTime] = sample().root!.children[1].values;
    expect(recurring.mode).toBe('recurring');
    expect(recurring.period).toEqual({ count: 1, unit: 'month' });
    expect(recurring.validFrom).toBe('2026-01-01');
    expect(oneTime.mode).toBe('one_time');
    expect(oneTime.interest).toEqual({
      rate: 3,
      period: { count: 1, unit: 'year' },
      basis: 'pro_mille',
      compound: true,
    });
  });

  it('reads the $computed overlay and ignores nothing else', () => {
    const doc = parseFinance(
      `$meta:\n  kind: finance-tree\nversion: 1\nroot:\n  name: r\n$computed:\n  computedAt: "2026-11-11T10:00:00Z"\n  nodes:\n    r:\n      perYear: 1200\n      perMonth: 100\n`,
      YAML,
    );
    expect(doc.computed?.computedAt).toBe('2026-11-11T10:00:00Z');
    expect(doc.computed?.nodes.r.perYear).toBe(1200);
    expect(doc.computed?.nodes.r.perWeek).toBe(0);
  });

  it('reads the JSON dual including the $meta wrapper', () => {
    const json = serializeFinance(sample(), JSON_MIME);
    expect(JSON.parse(json).$meta).toEqual({ kind: 'finance-tree' });
    expect(parseFinance(json, JSON_MIME)).toEqual(sample());
  });

  it('is lenient about a transiently incomplete value, strict about structure', () => {
    // A cleared date field must not throw the tab over to the raw editor.
    const doc = parseFinance(
      `$meta:\n  kind: finance-tree\nversion: 1\nroot:\n  name: r\n  values:\n    - value: 5\n      mode: one_time\n`,
      YAML,
    );
    expect(doc.root!.values[0].validFrom).toBeUndefined();

    expect(() => parseFinance('root:\n  title: nameless\n', YAML)).toThrow(FinanceCodecError);
    expect(() => parseFinance('root:\n  name: r\n  values:\n    - mode: one_time\n', YAML)).toThrow(
      FinanceCodecError,
    );
    expect(() => parseFinance('{ not json', JSON_MIME)).toThrow(FinanceCodecError);
  });

  it('rejects unsupported mime types and flags them as parse errors', () => {
    expect(() => parseFinance('x', 'text/markdown')).toThrow(FinanceCodecError);
    expect(isFinanceParseError(new FinanceCodecError('x'))).toBe(true);
    expect(isFinanceParseError(new Error('unrelated'))).toBe(false);
    expect(isFinanceMime('application/yaml')).toBe(true);
    expect(isFinanceMime('text/markdown')).toBe(false);
  });
});

describe('serializeFinance', () => {
  it('round-trips the model through YAML and JSON', () => {
    expect(parseFinance(serializeFinance(sample(), YAML), YAML)).toEqual(sample());
    expect(parseFinance(serializeFinance(sample(), JSON_MIME), JSON_MIME)).toEqual(sample());
  });

  it('is idempotent and prepends the header exactly once', () => {
    const once = serializeFinance(sample(), YAML);
    const twice = serializeFinance(parseFinance(once, YAML), YAML);
    expect(twice).toBe(once);
    expect(once.match(/^# vance finance-tree v1/gm)).toHaveLength(1);
    expect(once).toContain('kind: finance-tree');
  });

  it('writes the compact form: defaults are omitted', () => {
    const body = serializeFinance(
      {
        version: 1,
        root: {
          name: 'r',
          sign: 1,
          disabled: false,
          values: [{ value: 5, mode: 'recurring', period: { count: 1, unit: 'month' } }],
          children: [],
        },
      },
      YAML,
    );
    expect(body).not.toMatch(/sign:/);
    expect(body).not.toMatch(/disabled:/);
    expect(body).not.toMatch(/mode:/);
    expect(body).not.toMatch(/children:/);
    expect(body).not.toMatch(/title:/);
  });

  it('writes a computed overlay only when present', () => {
    const doc = sample();
    // The header comment names `$computed` too — only the key counts.
    expect(serializeFinance(doc, YAML)).not.toContain('$computed:');
    doc.computed = { computedAt: 'now', nodes: {} };
    const out = serializeFinance(doc, YAML);
    expect(out).toContain('$computed:');
    expect(parseFinance(out, YAML).computed?.computedAt).toBe('now');
  });
});
