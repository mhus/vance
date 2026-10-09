/**
 * Tests for the Facelift UA-token detection. The User-Agent is the only
 * channel the wrappers expose to the hosted website (the account WebViews
 * run sandboxed, without a preload), so the token contract is load-bearing:
 * every wrapper appends `VanceFacelift/<version>`, only the Electron
 * desktop additionally appends `VanceFaceliftDesktop/<version>`.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  getFaceliftDesktopVersion,
  getFaceliftVersion,
  isFacelift,
  isFaceliftDesktop,
} from './index';

const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 ' +
  '(KHTML, like Gecko) Mobile/15E148 VanceFacelift/0.1.0';

const IPAD_UA =
  'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 ' +
  '(KHTML, like Gecko) Mobile/15E148 VanceFacelift/0.1.0';

const DESKTOP_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) vancetope/0.1.0 Chrome/130.0.0.0 Electron/44.3.0 ' +
  'Safari/537.36 VanceFacelift/0.1.0 VanceFaceliftDesktop/0.1.0';

const BROWSER_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.36 ' +
  '(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';

function stubUserAgent(ua: string): void {
  vi.stubGlobal('navigator', { userAgent: ua });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('isFacelift', () => {
  it('is true inside every Facelift wrapper', () => {
    for (const ua of [IPHONE_UA, IPAD_UA, DESKTOP_UA]) {
      stubUserAgent(ua);
      expect(isFacelift(), ua).toBe(true);
    }
  });

  it('is false in a plain browser', () => {
    stubUserAgent(BROWSER_UA);
    expect(isFacelift()).toBe(false);
  });
});

describe('isFaceliftDesktop', () => {
  it('is true only for the Electron desktop wrapper', () => {
    stubUserAgent(DESKTOP_UA);
    expect(isFaceliftDesktop()).toBe(true);
  });

  it('is false on iPhone, iPad and in a plain browser', () => {
    for (const ua of [IPHONE_UA, IPAD_UA, BROWSER_UA]) {
      stubUserAgent(ua);
      expect(isFaceliftDesktop(), ua).toBe(false);
    }
  });
});

describe('version helpers', () => {
  it('extract the wrapper version from the tokens', () => {
    stubUserAgent(DESKTOP_UA);
    expect(getFaceliftVersion()).toBe('0.1.0');
    expect(getFaceliftDesktopVersion()).toBe('0.1.0');
  });

  it('return null for absent tokens', () => {
    stubUserAgent(IPHONE_UA);
    expect(getFaceliftVersion()).toBe('0.1.0');
    expect(getFaceliftDesktopVersion()).toBeNull();
  });
});
