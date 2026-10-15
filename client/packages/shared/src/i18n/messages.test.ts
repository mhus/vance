import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  localeLoaders,
  onI18nMessages,
  registerI18nMessages,
  registeredI18nBundles,
} from './messages';

/**
 * The registry exists for one race: host and addon boot in either order, and
 * neither can know which won. Both directions are therefore tested explicitly.
 */
describe('i18n message registry', () => {
  beforeEach(() => {
    // The store lives on globalThis by design (that is what makes it work
    // across federation bundles), so tests have to clear it themselves.
    (globalThis as { __VANCE_I18N_MESSAGES__?: unknown }).__VANCE_I18N_MESSAGES__ = undefined;
  });

  it('replays bundles registered before the host subscribed', () => {
    registerI18nMessages('canvas', { en: { canvas: { note: 'Note' } } });
    const sink = vi.fn();

    onI18nMessages(sink);

    expect(sink).toHaveBeenCalledTimes(1);
    expect(sink).toHaveBeenCalledWith('canvas', { en: { canvas: { note: 'Note' } } }, {});
  });

  it('forwards bundles registered after the host subscribed', () => {
    const sink = vi.fn();
    onI18nMessages(sink);

    registerI18nMessages('wiki', { de: { wiki: { note: 'Notiz' } } });

    expect(sink).toHaveBeenCalledTimes(1);
    expect(sink).toHaveBeenCalledWith('wiki', { de: { wiki: { note: 'Notiz' } } }, {});
  });

  it('replaces a bundle re-registered under the same id', () => {
    registerI18nMessages('canvas', { en: { canvas: { note: 'old' } } });
    registerI18nMessages('canvas', { en: { canvas: { note: 'new' } } });

    expect(registeredI18nBundles().size).toBe(1);
    expect(registeredI18nBundles().get('canvas')).toEqual({ en: { canvas: { note: 'new' } } });
  });

  it('reaches every subscriber, and stops at unsubscribe', () => {
    const first = vi.fn();
    const second = vi.fn();
    const off = onI18nMessages(first);
    onI18nMessages(second);

    off();
    registerI18nMessages('gtd', { en: {} });

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it('hands lazy locales to the sink without loading them', () => {
    const load = vi.fn(() => Promise.resolve({ wiki: { note: 'Note FR' } }));
    const sink = vi.fn();
    onI18nMessages(sink);

    registerI18nMessages('wiki', { en: { wiki: { note: 'Note' } } }, { fr: load });

    expect(sink.mock.calls[0][2].fr).toBe(load);
    expect(load).not.toHaveBeenCalled();
  });
});

describe('mixed versions', () => {
  beforeEach(() => {
    (globalThis as { __VANCE_I18N_MESSAGES__?: unknown }).__VANCE_I18N_MESSAGES__ = undefined;
  });

  it('replays a bundle an older copy stored without lazy locales', () => {
    // What an addon built against the previous registry leaves behind.
    (globalThis as { __VANCE_I18N_MESSAGES__?: unknown }).__VANCE_I18N_MESSAGES__ = {
      bundles: new Map([['workbook', { en: { workbook: {} } }]]),
      sinks: new Set(),
    };
    const sink = vi.fn();

    onI18nMessages(sink);

    expect(sink).toHaveBeenCalledWith('workbook', { en: { workbook: {} } }, undefined);
  });
});

describe('localeLoaders', () => {
  it('keys the glob result by file name and resolves the tree', async () => {
    const loaders = localeLoaders({
      './locales/fr.json': () => Promise.resolve({ a: 'b' }),
      './locales/zh.json': () => Promise.resolve({ a: 'c' }),
      './locales/README.md': () => Promise.resolve({}),
    });

    expect(Object.keys(loaders).sort()).toEqual(['fr', 'zh']);
    await expect(loaders.zh()).resolves.toEqual({ a: 'c' });
  });
});
