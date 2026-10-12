import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  app: {
    getPath: (kind: string) =>
      kind === 'home' ? '/home/tester' : '/tmp/vitest-vance-account-config',
  },
}));

import { rm } from 'node:fs/promises';

import { getAccountConfig, setConfineToWorkdir, setToolsEnabled, setWorkdir,
  setPackHome,
  setPackHomeForProject,
  removePackHomeForProject,
} from './accountConfig';

const TMP_DIR = '/tmp/vitest-vance-account-config';

describe('accountConfig', () => {
  afterEach(async () => {
    await rm(TMP_DIR, { recursive: true, force: true });
  });

  it('defaults to disabled tools and the home directory', async () => {
    const config = await getAccountConfig('acc-1');
    expect(config).toEqual({
      toolsEnabled: false,
      workdir: '/home/tester',
      confineToWorkdir: false,
      packHome: null,
      packHomeByProject: {},
    });
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
      confineToWorkdir: false,
      packHome: null,
      packHomeByProject: {},
    });

    await setToolsEnabled('acc-1', false);
    expect(await getAccountConfig('acc-1')).toEqual({
      toolsEnabled: false,
      workdir: '/Users/tester/sources',
      confineToWorkdir: false,
      packHome: null,
      packHomeByProject: {},
    });
  });

  it('persists the confinement switch without touching the rest', async () => {
    await setToolsEnabled('acc-1', true);
    await setWorkdir('acc-1', '/Users/tester/sources');
    await setConfineToWorkdir('acc-1', true);
    expect(await getAccountConfig('acc-1')).toEqual({
      toolsEnabled: true,
      workdir: '/Users/tester/sources',
      confineToWorkdir: true,
      packHome: null,
      packHomeByProject: {},
    });
  });

  it('keeps accounts isolated', async () => {
    await setWorkdir('acc-1', '/a');
    await setWorkdir('acc-2', '/b');
    expect((await getAccountConfig('acc-1')).workdir).toBe('/a');
    expect((await getAccountConfig('acc-2')).workdir).toBe('/b');
  });
});

describe('pack home', () => {
  it('defaults to the real home (null) and an empty per-project map', async () => {
    const c = await getAccountConfig('acc-pk');
    expect(c.packHome).toBeNull();
    expect(c.packHomeByProject).toEqual({});
  });

  it('persists the default home independently of the per-project entries', async () => {
    await setPackHome('acc-pk', '/data/pack-home');
    await setPackHomeForProject('acc-pk', 'acme/atlas', '/data/atlas-home');
    const c = await getAccountConfig('acc-pk');
    expect(c.packHome).toBe('/data/pack-home');
    expect(c.packHomeByProject['acme/atlas']).toBe('/data/atlas-home');
    // unrelated fields untouched
    expect(c.toolsEnabled).toBe(false);
  });

  it('removes a single per-project entry, keeps the rest', async () => {
    await setPackHomeForProject('acc-pk', 'acme/atlas', '/data/atlas-home');
    await setPackHomeForProject('acc-pk', 'acme/borealis', '/data/other');
    await removePackHomeForProject('acc-pk', 'acme/atlas');
    const c = await getAccountConfig('acc-pk');
    expect(c.packHomeByProject['acme/atlas']).toBeUndefined();
    expect(c.packHomeByProject['acme/borealis']).toBe('/data/other');
  });
});
