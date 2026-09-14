import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';

const brainFetch = vi.fn();
vi.mock('@vance/shared', () => ({ brainFetch: (...args: unknown[]) => brainFetch(...args) }));

const { useUsageReport } = await import('./useUsageReport');

function report(key: string): { bucketBy: string; buckets: { key: string }[] } {
  return { bucketBy: key, buckets: [{ key }] };
}

/**
 * Answers the five cuts in load order: summary, by-project, by-model,
 * by-caller, by-recipe. Each entry is a resolved value or the string
 * 'reject' for a failing endpoint.
 */
function cuts(...answers: unknown[]): void {
  brainFetch.mockImplementation(() => {
    const value = answers.shift();
    return value === 'reject' ? Promise.reject(new Error('boom')) : Promise.resolve(value);
  });
}

describe('useUsageReport', () => {
  beforeEach(() => {
    brainFetch.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('fills every cut when all five endpoints answer', async () => {
    cuts(
      report('day'),
      report('project'),
      report('model'),
      report('caller'),
      report('recipe'),
    );
    const api = useUsageReport();

    await api.loadAll({});

    expect(api.summary.value?.bucketBy).toBe('day');
    expect(api.byProject.value?.bucketBy).toBe('project');
    expect(api.byModel.value?.bucketBy).toBe('model');
    expect(api.byCaller.value?.bucketBy).toBe('caller');
    expect(api.byRecipe.value?.bucketBy).toBe('recipe');
    expect(api.error.value).toBeNull();
    expect(api.loading.value).toBe(false);
  });

  it('keeps the four answering cuts when one endpoint fails', async () => {
    cuts('reject', report('project'), report('model'), report('caller'), report('recipe'));
    const api = useUsageReport();

    await api.loadAll({});

    // The failure that motivated this policy: the summary 500 on MongoDB
    // 4.4 used to wipe all five cuts via reset(), and the view read as
    // "nothing was recorded at all".
    expect(api.summary.value).toBeNull();
    expect(api.byProject.value?.bucketBy).toBe('project');
    expect(api.byModel.value?.bucketBy).toBe('model');
    expect(api.byCaller.value?.bucketBy).toBe('caller');
    expect(api.byRecipe.value?.bucketBy).toBe('recipe');
    expect(api.error.value).toBe('boom');
    expect(api.loading.value).toBe(false);
  });

  it('reports the failure and stays empty when all endpoints fail', async () => {
    cuts('reject', 'reject', 'reject', 'reject', 'reject');
    const api = useUsageReport();

    await api.loadAll({});

    expect(api.summary.value).toBeNull();
    expect(api.byProject.value).toBeNull();
    expect(api.byModel.value).toBeNull();
    expect(api.byCaller.value).toBeNull();
    expect(api.byRecipe.value).toBeNull();
    expect(api.error.value).toBe('boom');
  });

  it('reset() clears cuts and error', async () => {
    cuts(report('day'), report('project'), report('model'), report('caller'), report('recipe'));
    const api = useUsageReport();
    await api.loadAll({});

    api.reset();

    expect(api.summary.value).toBeNull();
    expect(api.byRecipe.value).toBeNull();
    expect(api.error.value).toBeNull();
  });
});
