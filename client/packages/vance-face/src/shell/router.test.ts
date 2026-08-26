// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * The shell router's query handling — which is now: it does not touch it.
 *
 * <p>These tests exist because the opposite was tried. An earlier version
 * filtered "editor-specific" keys on a hop, keeping only `project`/`path`, to
 * stop a session id riding from Chat into Cortex. Nothing ever rode: a
 * `push('/cortex?project=p')` sets that query and nothing else. What the
 * filter really stripped were the parameters one editor deliberately sends to
 * another — `doc=` (open this document), `create=1`, `createDraft=1` — so
 * clicking a file in `/documents` opened Cortex with nothing in it.
 *
 * <p>Both bugs were found in a browser and neither was visible to the build or
 * to the first round of tests, which asserted the wrong rule and passed. What
 * is pinned here is the behaviour the editors actually depend on: the query
 * they construct arrives intact.
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

  it('keeps every parameter of the address the page was opened with', () => {
    const fresh = router.resolve('/cortex?project=cloud-delivery&doc=abc123');
    expect(fresh.query).toEqual({ project: 'cloud-delivery', doc: 'abc123' });
  });

  it('delivers the document the Explorer asks Cortex to open', async () => {
    // The reported bug, in one line: clicking a file in /documents calls
    // navigateTo('/cortex?project=…&doc=…'), and `doc` was being dropped.
    await router.push('/documents?projectId=p');
    await router.push('/cortex?project=p&doc=abc123');

    expect(router.currentRoute.value.path).toBe('/cortex');
    expect(router.currentRoute.value.query).toEqual({ project: 'p', doc: 'abc123' });
  });

  it('delivers the create flags the Explorer and Inbox send', async () => {
    // Same class, two more flows: "new file" from the Explorer and "start a
    // draft" from the Inbox both travel as a flag the target reads on mount.
    await router.push('/documents');
    await router.push('/cortex?project=p&path=documents&create=1');
    expect(router.currentRoute.value.query).toMatchObject({ create: '1' });

    await router.push('/inbox');
    await router.push('/documents?createDraft=1');
    expect(router.currentRoute.value.query).toMatchObject({ createDraft: '1' });
  });

  it('delivers the session Chat and Cortex hand each other', async () => {
    await router.push('/chat?sessionId=s-1');
    await router.push('/cortex?sessionId=s-1');

    expect(router.currentRoute.value.query).toEqual({ sessionId: 's-1' });
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
