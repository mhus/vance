// @vitest-environment jsdom
// The module under test is storage-backed, so a DOM is the point here, not
// an accident of importing something that touches one.
import { beforeEach, describe, expect, it, vi } from 'vitest';

const getSessionData = vi.fn();
vi.mock('./webUiSession', () => ({ getSessionData: () => getSessionData() }));

// Desktop-bridge gate: null = browser (sessionStorage), an object = inside
// the desktop app (localStorage — the app has no tabs, and its WebContents
// dies on every app restart).
const getDesktopTools = vi.fn<() => unknown>();
vi.mock('@vance/shared', () => ({ getDesktopTools: () => getDesktopTools() }));

const { recallProject, rememberProject } = await import('./lastProject');

const ACME = { tenantId: 'acme', username: 'mhus' };

describe('lastProject', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    window.localStorage.clear();
    getSessionData.mockReturnValue(ACME);
    getDesktopTools.mockReturnValue(null);
  });

  it('recall_afterRemember_returnsTheProject', () => {
    rememberProject('atlas');
    expect(recallProject()).toBe('atlas');
  });

  it('recall_nothingRemembered_returnsNull', () => {
    expect(recallProject()).toBeNull();
  });

  // The whole reason the write side is lenient: editors pass "no project"
  // through constantly, and none of those moments mean the reader left.
  it('remember_blank_leavesThePreviousProject', () => {
    rememberProject('atlas');
    rememberProject(null);
    rememberProject('');
    expect(recallProject()).toBe('atlas');
  });

  it('recall_projectNotSelectableHere_returnsNull', () => {
    rememberProject('atlas');
    expect(recallProject(['other'])).toBeNull();
    expect(recallProject(['other', 'atlas'])).toBe('atlas');
  });

  // A different account in the same tab must not inherit the previous one's
  // project — the key carries tenant and login for exactly this.
  it('recall_afterAccountSwitch_doesNotSeeTheOtherAccount', () => {
    rememberProject('atlas');
    getSessionData.mockReturnValue({ tenantId: 'acme', username: 'trillian' });
    expect(recallProject()).toBeNull();
    getSessionData.mockReturnValue({ tenantId: 'other', username: 'mhus' });
    expect(recallProject()).toBeNull();
    getSessionData.mockReturnValue(ACME);
    expect(recallProject()).toBe('atlas');
  });

  it('recall_noSession_fallsBackToTheSharedKeyWithoutThrowing', () => {
    getSessionData.mockReturnValue(null);
    rememberProject('atlas');
    expect(recallProject()).toBe('atlas');
  });

  // Inside the desktop app the bridge is present — the memory moves to
  // localStorage so it survives app restarts (the WebContentsView dies with
  // the app; the per-account partition keeps localStorage on disk).
  it('desktopBridge_usesLocalStorage_notSessionStorage', () => {
    getDesktopTools.mockReturnValue({ version: '1' });
    rememberProject('atlas');
    expect(window.localStorage.getItem('vance.lastProject.acme.mhus')).toBe('atlas');
    expect(window.sessionStorage.length).toBe(0);
    expect(recallProject()).toBe('atlas');
  });
});
