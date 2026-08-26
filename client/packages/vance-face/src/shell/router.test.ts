// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The shell router's query policy.
 *
 * <p>There is one rule and it has two halves that pull in opposite
 * directions: a hop between editors must drop the parameters the previous one
 * owned (a session id means nothing to Cortex, and it would ride along in
 * every link the reader copies afterwards), while the address someone
 * *opened* must be honoured verbatim.
 *
 * <p>The second half is here because the first one broke it. The guard
 * originally ran on every navigation including the initial one, so
 * `/cortex?project=x&doc=y` arrived as `/cortex?project=x` and opened no
 * document — deep links from mail, from the inbox, from a bookmark, all
 * silently truncated. Found in the browser, not by the build.
 */

vi.mock('@/platform/webUiSession', () => ({ getSessionData: () => null }));

// The route components are the real editors; loading them here would drag the
// whole application in. The routing decision does not depend on them.
vi.mock('./LandingView.vue', () => ({ default: { template: '<div/>' } }));
vi.mock('@/cortex/EditorApp.vue', () => ({ default: { template: '<div/>' } }));
vi.mock('@/chat/ChatApp.vue', () => ({ default: { template: '<div/>' } }));
vi.mock('@/inbox/InboxApp.vue', () => ({ default: { template: '<div/>' } }));
vi.mock('@/document/DocumentExplorerApp.vue', () => ({ default: { template: '<div/>' } }));

import { router } from './router';

describe('shell router query policy', () => {
  beforeEach(async () => {
    // Land on the launcher so each case starts from a real previous route.
    await router.replace('/');
    await router.isReady();
  });

  it('keeps every parameter of the address the page was opened with', async () => {
    // Freshly imported router: `from` has no matched record, which is how the
    // initial navigation is told apart from a hop.
    const fresh = router.resolve('/cortex?project=cloud-delivery&doc=abc123');
    expect(fresh.query).toEqual({ project: 'cloud-delivery', doc: 'abc123' });
  });

  it('drops the previous editor’s parameters on a hop', async () => {
    await router.push('/chat?sessionId=s-1');
    await router.push('/cortex?project=p&sessionId=s-1');

    expect(router.currentRoute.value.path).toBe('/cortex');
    expect(router.currentRoute.value.query).toEqual({ project: 'p' });
  });

  it('carries the shared parameters across a hop', async () => {
    await router.push('/documents?projectId=p&path=documents/');
    await router.push('/cortex?projectId=p&path=documents/&createDraft=1');

    expect(router.currentRoute.value.query).toEqual({
      projectId: 'p',
      path: 'documents/',
    });
  });

  it('leaves a navigation within the same editor untouched', async () => {
    // Cortex rewrites its own URL constantly; the guard must not fight it.
    await router.push('/cortex?project=p');
    await router.push('/cortex?project=p&open=doc-1&entry=x');

    expect(router.currentRoute.value.query).toEqual({
      project: 'p',
      open: 'doc-1',
      entry: 'x',
    });
  });

  it('redirects the legacy .html forms and keeps their query', async () => {
    await router.push('/cortex.html?project=p&path=documents/');

    expect(router.currentRoute.value.path).toBe('/cortex');
    expect(router.currentRoute.value.query).toMatchObject({ project: 'p' });
  });

  it('sends an unknown path to the launcher rather than a dead end', async () => {
    await router.push('/nope/whatever');

    expect(router.currentRoute.value.path).toBe('/');
  });

  it('gives every route a tab title of its own', () => {
    // As separate HTML files each had its own <title>; as routes they would
    // all read "Vance" and a window becomes unfindable among twenty.
    for (const path of ['/', '/cortex', '/chat', '/inbox', '/documents']) {
      expect(router.resolve(path).meta.title).toBeTypeOf('string');
    }
  });
});
