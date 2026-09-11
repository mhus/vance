// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The module under test must not talk to the network or read a real
// identity — both live in `@vance/shared`, so the mock is the seam that
// makes this a test of the injection contract, not of the REST client.
const brainFetchText = vi.fn();
const getTenantId = vi.fn();

vi.mock('@vance/shared', () => ({
  brainFetchText: (...args: unknown[]) => brainFetchText(...args),
  getTenantId: () => getTenantId(),
}));

import { applyTenantCustomCss } from './tenantCustomCss';

const STYLE_ID = 'vance-tenant-custom-css';

function styleElement(): HTMLStyleElement | null {
  return document.getElementById(STYLE_ID) as HTMLStyleElement | null;
}

describe('applyTenantCustomCss', () => {
  beforeEach(() => {
    brainFetchText.mockReset();
    getTenantId.mockReset();
    document.head.innerHTML = '';
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('injects the served css as a style element in the head', async () => {
    getTenantId.mockReturnValue('acme');
    brainFetchText.mockResolvedValue('body { color: red; }');

    await applyTenantCustomCss();

    expect(brainFetchText).toHaveBeenCalledWith('ui/custom-css');
    expect(styleElement()?.textContent).toBe('body { color: red; }');
  });

  it('treats a missing stylesheet (null) as empty, not as an error', async () => {
    getTenantId.mockReturnValue('acme');
    brainFetchText.mockResolvedValue(null);

    await applyTenantCustomCss();

    expect(styleElement()).not.toBeNull();
    expect(styleElement()?.textContent).toBe('');
  });

  it('is a no-op without a session — the login page has no tenant', async () => {
    getTenantId.mockReturnValue(null);

    await applyTenantCustomCss();

    expect(brainFetchText).not.toHaveBeenCalled();
    expect(styleElement()).toBeNull();
  });

  it('fails open on a fetch error and injects nothing', async () => {
    getTenantId.mockReturnValue('acme');
    brainFetchText.mockRejectedValue(new Error('network down'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    await applyTenantCustomCss();

    expect(styleElement()).toBeNull();
    expect(warn).toHaveBeenCalledOnce();
  });

  it('reuses the element across calls and only rewrites on change', async () => {
    getTenantId.mockReturnValue('acme');
    brainFetchText.mockResolvedValue('a { color: red }');
    await applyTenantCustomCss();
    const first = styleElement();
    expect(first).not.toBeNull();

    // Second load answers the same css — the element instance must
    // survive (no duplicate sheets piling up on repeated boots).
    await applyTenantCustomCss();
    const second = styleElement();
    expect(second).toBe(first);
    expect(document.querySelectorAll(`#${STYLE_ID}`)).toHaveLength(1);

    // A changed stylesheet rewrites the same element.
    brainFetchText.mockResolvedValue('a { color: blue }');
    await applyTenantCustomCss();
    expect(styleElement()?.textContent).toBe('a { color: blue }');
    expect(document.querySelectorAll(`#${STYLE_ID}`)).toHaveLength(1);
  });
});
