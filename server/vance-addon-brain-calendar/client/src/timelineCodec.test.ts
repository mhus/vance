import { describe, expect, it } from 'vitest';
import {
  emptyTimeline,
  instantSeconds,
  parseTimeline,
  serializeTimeline,
  timelinePosition,
  type TimelineAxis,
} from './timelineCodec';

/**
 * The client codec is the mirror of the server's `TimelineCodec` +
 * `TimelineScale`. A divergence here does not throw — it draws entries in
 * the wrong place while `kind_validate` reports the document as clean, which
 * is the one failure mode nobody would think to check. These tests pin the
 * shared decisions: the `ago` projection, the permissive instant grammar, and
 * what the promotion drops.
 */

const YAML = 'application/yaml';
const JSON_MIME = 'application/json';

function axis(
  mode: 'numeric' | 'datetime',
  direction: 'forward' | 'ago' = 'forward',
): TimelineAxis {
  return { mode, direction, extra: {} };
}

describe('timelinePosition', () => {
  it('keeps a numeric value on a forward axis', () => {
    expect(timelinePosition(axis('numeric'), '12.5')).toBe(12.5);
  });

  it('orders the larger number earlier on an ago axis', () => {
    const jura = timelinePosition(axis('numeric', 'ago'), '201.4')!;
    const kreide = timelinePosition(axis('numeric', 'ago'), '143.1')!;
    expect(jura).toBeLessThan(kreide);
  });

  it('rejects a value carrying its unit', () => {
    // The unit belongs in axis.unit; this is the mistake the validator names.
    expect(timelinePosition(axis('numeric'), '201.4 Ma')).toBeNull();
  });

  it('rejects a bare number on a datetime axis and an ISO date on a numeric one', () => {
    expect(timelinePosition(axis('datetime'), '201.4')).toBeNull();
    expect(timelinePosition(axis('numeric'), '1969-07-20')).toBeNull();
  });

  it('is null for blank and missing values', () => {
    expect(timelinePosition(axis('numeric'), null)).toBeNull();
    expect(timelinePosition(axis('numeric'), '   ')).toBeNull();
  });
});

describe('instantSeconds', () => {
  it('reads minute and second precision', () => {
    const earlier = instantSeconds('2026-03-04T21:40')!;
    const later = instantSeconds('2026-03-04T21:47:30')!;
    expect(later - earlier).toBe(450);
  });

  it('honours an explicit offset in both notations', () => {
    const utc = instantSeconds('2026-03-04T21:00Z');
    expect(instantSeconds('2026-03-04T22:00+01:00')).toBe(utc);
    expect(instantSeconds('2026-03-04T22:00+0100')).toBe(utc);
  });

  it('treats a bare year as the start of that year', () => {
    expect(instantSeconds('1969')!).toBeLessThan(instantSeconds('1969-07-20')!);
  });

  it('reads a two-digit year as that year, not as the 1900s', () => {
    // Date.UTC would map year 44 into 1944 — 1900 years late.
    const caesar = instantSeconds('-0044-03-15')!;
    const romanEmpire = instantSeconds('0044-01-01')!;
    expect(caesar).toBeLessThan(romanEmpire);
    expect(romanEmpire).toBeLessThan(instantSeconds('1900-01-01')!);
  });

  it('rejects an impossible date instead of rolling it over', () => {
    expect(instantSeconds('2026-02-31')).toBeNull();
    expect(instantSeconds('2026-13-01')).toBeNull();
    expect(instantSeconds('2026-03-04T25:00')).toBeNull();
  });

  it('agrees with the epoch for a known instant', () => {
    expect(instantSeconds('1970-01-01T00:00:00Z')).toBe(0);
  });
});

describe('parseTimeline', () => {
  it('reads axis, lanes and entries', () => {
    const doc = parseTimeline(`$meta:
  kind: timeline
title: Mesozoikum
axis:
  mode: numeric
  unit: Ma
  direction: ago
lanes:
  - id: strat
    title: Stratigraphie
entries:
  - id: jura
    title: Jura
    from: 201.4
    to: 143.1
    lane: strat
  - id: oberjura
    title: Oberjura
    from: 161.5
    to: 143.1
    parent: jura
`, YAML);

    expect(doc.title).toBe('Mesozoikum');
    expect(doc.axis.direction).toBe('ago');
    expect(doc.axis.unit).toBe('Ma');
    expect(doc.lanes).toEqual([{ id: 'strat', title: 'Stratigraphie', color: undefined }]);
    expect(doc.entries).toHaveLength(2);
    expect(doc.entries[0].from).toBe('201.4');
    expect(doc.entries[1].parent).toBe('jura');
  });

  it('reads at / start / end / until as from / to without leaking them into extra', () => {
    const doc = parseTimeline(`$meta:
  kind: timeline
axis: { mode: datetime }
entries:
  - title: Anruf
    at: "2026-03-04T21:40"
  - title: Brand
    start: "2026-03-04T22:10"
    end: "2026-03-04T23:30"
`, YAML);

    expect(doc.entries[0].from).toBe('2026-03-04T21:40');
    expect(doc.entries[1].to).toBe('2026-03-04T23:30');
    expect(doc.entries[0].extra).toEqual({});
    expect(doc.entries[1].extra).toEqual({});
  });

  it('drops entries without a title or a start position', () => {
    const doc = parseTimeline(`$meta:
  kind: timeline
axis: { mode: numeric }
entries:
  - title: Keeps
    from: 5
  - from: 7
  - title: No position
`, YAML);

    expect(doc.entries.map(e => e.title)).toEqual(['Keeps']);
  });

  it('keeps an unquoted ISO date readable', () => {
    // js-yaml promotes it to a Date; without coercion the entry would drop.
    const doc = parseTimeline(`$meta:
  kind: timeline
axis: { mode: datetime }
entries:
  - title: Mondlandung
    from: 1969-07-20
`, YAML);

    expect(doc.entries[0].from).toBe('1969-07-20');
  });

  it('accepts lanes as ids, as objects and as a map', () => {
    const ids = (body: string) => parseTimeline(body, YAML).lanes.map(l => l.id);
    const head = '$meta:\n  kind: timeline\naxis: { mode: numeric }\nentries: []\n';

    expect(ids(head + 'lanes: [a, b]\n')).toEqual(['a', 'b']);
    expect(ids(head + 'lanes:\n  - id: a\n  - id: b\n')).toEqual(['a', 'b']);
    expect(ids(head + 'lanes:\n  a: { title: A }\n  b: { title: B }\n')).toEqual(['a', 'b']);
  });

  it('falls back on an unknown axis mode instead of failing', () => {
    const doc = parseTimeline(
      '$meta:\n  kind: timeline\naxis: { mode: geological }\nentries: []\n', YAML);
    expect(doc.axis.mode).toBe('numeric');
  });

  it('rejects markdown', () => {
    expect(() => parseTimeline('# nope', 'text/markdown')).toThrow(/Unsupported mime type/);
  });

  it('reads an empty body as an empty timeline', () => {
    expect(parseTimeline('', YAML)).toEqual(emptyTimeline());
  });
});

describe('serializeTimeline', () => {
  it('round-trips a document through YAML', () => {
    const body = `$meta:
  kind: timeline
title: Tathergang
axis:
  mode: datetime
  label: Nacht vom 4. auf den 5.
entries:
  - id: e1
    title: Anruf
    from: '2026-03-04T21:40'
    fromLatest: '2026-03-04T22:05'
    tags:
      - beleg
    confidence: mittel
`;
    const first = parseTimeline(body, YAML);
    const second = parseTimeline(serializeTimeline(first, YAML), YAML);

    expect(second.entries).toEqual(first.entries);
    expect(second.axis).toEqual(first.axis);
    expect(second.entries[0].extra).toEqual({ confidence: 'mittel' });
  });

  it('emits plain numbers unquoted and omits the default direction', () => {
    const doc = parseTimeline(
      '$meta:\n  kind: timeline\naxis: { mode: numeric }\n'
      + 'entries:\n  - title: X\n    from: 201.4\n', YAML);

    const yaml = serializeTimeline(doc, YAML);

    expect(yaml).toContain('from: 201.4');
    expect(yaml).not.toContain("'201.4'");
    expect(yaml).not.toContain('direction:');
  });

  it('round-trips through JSON too', () => {
    const doc = parseTimeline(
      '{"$meta":{"kind":"timeline"},"axis":{"mode":"numeric","direction":"ago"},'
      + '"entries":[{"id":"a","title":"A","from":"201.4","to":"143.1"}]}', JSON_MIME);

    const again = parseTimeline(serializeTimeline(doc, JSON_MIME), JSON_MIME);

    expect(again.entries).toEqual(doc.entries);
    expect(again.axis.direction).toBe('ago');
  });
});
