// @vitest-environment jsdom
/**
 * The one thing about {@link replaceUrl} that a caller can get wrong.
 *
 * Every editor in the shell writes its state into the URL and reads it back
 * out of `window.location.search`. While that write was `history.replaceState`
 * it landed synchronously, so writing and then reading in the same turn was
 * safe. Through the router it is not: the address bar follows a few microtasks
 * later. Cortex's starred-tile handoff did exactly that — resolve the path,
 * write `?open=…&doc=…`, then restore the view from the address — and restored
 * the *old* address, ending on an empty editor.
 *
 * So this test asserts the gap exists (otherwise it is testing nothing) and
 * that awaiting closes it.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import { createRouter, createWebHistory } from 'vue-router';
import { bindRouter, replaceUrl } from './navigate';

const Blank = { template: '<div/>' };

function makeRouter() {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', component: Blank },
      { path: '/cortex', component: Blank },
    ],
  });
}

describe('replaceUrl through the router', () => {
  beforeEach(async () => {
    window.history.replaceState(null, '', '/cortex');
    const router = makeRouter();
    bindRouter(router);
    await router.replace('/cortex');
  });

  it('replaceUrl_routerBound_addressBarLagsUntilAwaited', async () => {
    const pending = replaceUrl('/cortex?open=abc&doc=abc');

    // Not yet: the router is still running its navigation. A caller reading
    // the address here sees the URL it just replaced.
    expect(window.location.search).toBe('');

    await pending;

    expect(window.location.search).toBe('?open=abc&doc=abc');
  });
});
