import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  app: {
    getPath: (kind: string) =>
      kind === 'home' ? '/home/tester' : '/tmp/vitest-vance-account-config',
  },
}));

import { rm } from 'node:fs/promises';

import { getAccountConfig, setToolsEnabled, setWorkdir } from './accountConfig';

const TMP_DIR = '/tmp/vitest-vance-account-config';

describe('accountConfig', () => {
  afterEach(async () => {
    await rm(TMP_DIR, { recursive: true, force: true });
  });

  it('defaults to disabled tools and the home directory', async () => {
    const config = await getAccountConfig('acc-1');
    expect(config).toEqual({ toolsEnabled: false, workdir: '/home/tester' });
  });

  it('persists the working directory per account', async () => {
    await setWorkdir('acc-1', '/Users/tester/sources');
    const config = await getAccountConfig('acc-1');
    expect(config.workdir).toBe('/Users/tester/sources');
  });

  it('changing the workdir keeps the release flag (and vice versa)', async () => {
    await setToolsEnabled('acc-1', true);
    await setWorkdir('acc-1', '/Users/tester/sources');
    expect(await getAccountConfig('acc-1')).toEqual({
      toolsEnabled: true,
      workdir: '/Users/tester/sources',
    });

    await setToolsEnabled('acc-1', false);
    expect(await getAccountConfig('acc-1')).toEqual({
      toolsEnabled: false,
      workdir: '/Users/tester/sources',
    });
  });

  it('keeps accounts isolated', async () => {
    await setWorkdir('acc-1', '/a');
    await setWorkdir('acc-2', '/b');
    expect((await getAccountConfig('acc-1')).workdir).toBe('/a');
    expect((await getAccountConfig('acc-2')).workdir).toBe('/b');
  });
});
