// Failure-path tests for the `chart` kind-codec (TS side).
//
// The parity test covers well-formed bodies; this one pins the errors,
// because ChartView renders the thrown message to the user instead of a
// blank canvas. A syntax error that produced no message left the author
// with an empty chart and nothing to debug.
import { describe, it, expect } from 'vitest';
import { parseChart } from './chartCodec';

const YAML = 'application/yaml';

describe('parseChart — YAML syntax errors', () => {
  it('throws a readable error when a plain scalar contains a colon-space', () => {
    // `subtitle: Quelle: llm-stats.com` is not a string — YAML reads the
    // second `: ` as a nested mapping and rejects the document. The whole
    // body dies, not just that key, so the symptom is a chart that renders
    // nothing at all.
    const body = [
      '$meta:',
      '  kind: chart',
      'chart:',
      '  chartType: bar',
      '  subtitle: Quelle: llm-stats.com | Blau = proprietary',
      'series:',
      '  - name: Score',
      '    data:',
      '      - { x: A, y: 1 }',
    ].join('\n');

    expect(() => parseChart(body, YAML)).toThrow(/Invalid YAML/);
  });

  it('accepts the same subtitle once it is quoted', () => {
    const body = [
      '$meta:',
      '  kind: chart',
      'chart:',
      '  chartType: bar',
      '  subtitle: "Quelle: llm-stats.com | Blau = proprietary"',
      'series:',
      '  - name: Score',
      '    data:',
      '      - { x: A, y: 1 }',
    ].join('\n');

    const doc = parseChart(body, YAML);
    expect(doc.chart.subtitle).toBe('Quelle: llm-stats.com | Blau = proprietary');
    expect(doc.series).toHaveLength(1);
  });
});

describe('parseChart — axis keys are a closed list', () => {
  it('drops an ECharts-style xAxis.name instead of using it as the label', () => {
    // The Vance field is `label`. `name` is what ECharts calls it, and it
    // is dropped silently — documented in the kind-chart manual so the
    // model stops writing it.
    const body = [
      '$meta:',
      '  kind: chart',
      'chart:',
      '  chartType: bar',
      'xAxis: { type: category, name: Model, axisLabel: { rotate: 45 } }',
      'series:',
      '  - name: Score',
      '    data:',
      '      - { x: A, y: 1 }',
    ].join('\n');

    const doc = parseChart(body, YAML);
    expect(doc.xAxis?.label).toBeUndefined();
    expect(doc.xAxis).not.toHaveProperty('name');
    expect(doc.xAxis).not.toHaveProperty('axisLabel');
  });

  it('keeps an explicit categories list in authored order', () => {
    // Multi-series category charts depend on this: without it the tick
    // list comes from the first series only and later series lose their
    // slots. Order is the rendered bar order, so it must survive verbatim.
    const body = [
      '$meta:',
      '  kind: chart',
      'chart:',
      '  chartType: bar',
      'xAxis:',
      '  type: category',
      '  label: Model',
      '  categories: ["Gamma", "Alpha", "Beta"]',
      'series:',
      '  - name: Group A',
      '    data:',
      '      - { x: Gamma, y: 3 }',
      '  - name: Group B',
      '    data:',
      '      - { x: Alpha, y: 1 }',
      '      - { x: Beta, y: 2 }',
    ].join('\n');

    const doc = parseChart(body, YAML);
    expect(doc.xAxis?.categories).toEqual(['Gamma', 'Alpha', 'Beta']);
    expect(doc.xAxis?.label).toBe('Model');
    expect(doc.series.map((s) => s.name)).toEqual(['Group A', 'Group B']);
  });
});
