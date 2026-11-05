import { describe, expect, it } from 'vitest';
import en from './en';

/**
 * Every literal key the run view and the workflow drawing look up must exist
 * in the English bundle. Neither `vue-tsc` nor the build notices a missing key —
 * the page just shows the raw key, which is how `runs.status.*` and
 * `runs.sidebar.*` went unseen until someone opened the page.
 */
const sources = import.meta.glob(
  [
    '../runs/*.vue',
    '../workflowGraph/*.vue',
    '../kindViews/WorkflowFlowView.vue',
    '../insights/WorkflowsTab.vue',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>;

function lookup(key: string): unknown {
  return key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
    en,
  );
}

describe('i18n keys of the run view and the workflow drawing', () => {
  it('finds source files to check', () => {
    expect(Object.keys(sources).length).toBeGreaterThan(4);
  });

  it('has every literal key in en.ts', () => {
    const missing: string[] = [];
    for (const [file, text] of Object.entries(sources)) {
      for (const m of text.matchAll(/(?:\$t|\bt)\(\s*['"`]([a-zA-Z0-9_.]+)['"`]/g)) {
        if (lookup(m[1]) === undefined) missing.push(`${file} :: ${m[1]}`);
      }
    }
    expect(missing).toEqual([]);
  });

  it('has the namespace of every computed key (runs.status.${…}, runs.action.${…})', () => {
    const missing: string[] = [];
    for (const [file, text] of Object.entries(sources)) {
      for (const m of text.matchAll(/(?:\$t|\bt)\(\s*`([a-zA-Z0-9_.]+)\.\$\{/g)) {
        const ns = lookup(m[1]);
        if (!ns || typeof ns !== 'object') missing.push(`${file} :: ${m[1]}.*`);
      }
    }
    expect(missing).toEqual([]);
  });
});
