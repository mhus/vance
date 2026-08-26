// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, ref, type Ref } from 'vue';
import { mount } from '@vue/test-utils';

/**
 * The call-side half of the leak guard.
 *
 * <p>`wsConnectionStore.test.ts` proves the store's contract: every
 * registration hands back an unregister that restores the previous state
 * exactly. That says nothing about whether the components *call* it — and
 * since the workspace cluster, that is where the risk moved. Leaving an editor
 * unmounts a component instead of destroying the JS realm, so a composable
 * that forgets its cleanup keeps its handler for the rest of the working day.
 *
 * <p>Tested through the composables rather than through the editors, because
 * that is where the subscription actually lives — the editors delegate. Two
 * earlier attempts to measure this in a browser failed for reasons worth
 * remembering: a dynamic `import()` from the page gets a *second* module
 * instance in dev (counts read 0 because nobody writes to that one), and the
 * obvious route cycle (`/` → `/chat` → `/documents` → `/`) subscribes to
 * nothing at all — twelve route changes produced only pings on the wire. A
 * green test over those surfaces would have checked nothing.
 *
 * <p>Needs a DOM because the composables clean up in `onBeforeUnmount`, which
 * requires a real component instance. That is what this file's
 * `@vitest-environment` line buys; the rest of the suite stays on Node.
 */

/**
 * `useDocumentPrefixReaction` lives in `@vance/components`, which cannot import
 * vance-face's store — it goes through the `getVanceWs()` bridge that
 * `bootWeb` fills in at boot. Wiring it here is not test decoration: without
 * it the composable's `tryGetWs()` swallows the missing bridge and quietly
 * registers nothing, so the test would pass while proving the opposite of what
 * it claims. (It did, once.)
 */
const bridge = vi.hoisted(() => ({ impl: null as Record<string, unknown> | null }));

vi.mock('@vance/shared', () => ({
  BrainWebSocket: { connect: vi.fn() },
  getRestConfig: () => ({ baseUrl: '' }),
  getTenantId: () => 'acme',
  setActiveSessionId: vi.fn(),
  WebSocketRequestError: class extends Error {},
  getVanceWs: () => {
    if (!bridge.impl) throw new Error('ws bridge not configured');
    return bridge.impl;
  },
}));
vi.mock('@/platform/webUiSession', () => ({ getSessionData: () => null }));

import {
  onDocumentChangedPrefix,
  onDocumentPrefixReconnect,
  subscriptionCounts,
} from './wsConnectionStore';

// Only the two registrations are real. The subscribe/unsubscribe pair goes to
// the wire, and a unit test that needs a socket is a test about the socket.
bridge.impl = {
  onDocumentChangedPrefix,
  onDocumentPrefixReconnect,
  subscribeDocumentPrefix: async () => {},
  unsubscribeDocumentPrefix: async () => {},
};
import { useDocumentChangeReaction } from '@/composables/useDocumentChangeReaction';
import { useDocumentPrefixReaction } from '@vance/components';

function total(): number {
  return Object.values(subscriptionCounts()).reduce((a, b) => a + b, 0);
}

/** A component whose only job is to run a composable and then go away. */
function harness(run: () => void) {
  return defineComponent({
    setup() {
      run();
      return () => h('div');
    },
  });
}

describe('composable subscription lifecycle', () => {
  beforeEach(() => {
    expect(total()).toBe(0);
  });

  it('useDocumentChangeReaction releases its handler on unmount', () => {
    const path: Ref<string | null> = ref('documents/a.md');
    const wrapper = mount(harness(() => {
      useDocumentChangeReaction({ path, tryApply: () => 'applied' });
    }));

    expect(subscriptionCounts().documentChangedListeners).toBe(1);

    wrapper.unmount();

    expect(total()).toBe(0);
  });

  it('useDocumentChangeReaction swaps rather than stacks when the path changes', async () => {
    // The Cortex case: one tab, many documents over its lifetime. A swap that
    // forgot to release the old path would leak once per document opened —
    // the slowest possible leak to notice and the easiest to cause.
    const path: Ref<string | null> = ref('documents/a.md');
    const wrapper = mount(harness(() => {
      useDocumentChangeReaction({ path, tryApply: () => 'applied' });
    }));

    for (const next of ['documents/b.md', 'documents/c.md', 'documents/d.md']) {
      path.value = next;
      await wrapper.vm.$nextTick();
      expect(subscriptionCounts().documentChangedListeners).toBe(1);
    }

    wrapper.unmount();
    expect(total()).toBe(0);
  });

  it('useDocumentPrefixReaction releases both of its handlers on unmount', () => {
    // Registers twice — the change listener and the reconnect-replay one.
    // Releasing only the first would leave the quieter half behind.
    const prefix: Ref<string | null> = ref('documents/');
    const wrapper = mount(harness(() => {
      useDocumentPrefixReaction({ prefix, onRemoteChange: () => {} });
    }));

    expect(total()).toBeGreaterThan(0);

    wrapper.unmount();

    expect(total()).toBe(0);
  });

  it('a route cycle of mounts and unmounts accumulates nothing', () => {
    // The property §4.7 asks for, expressed where it is actually decidable:
    // ten editor visits in a row leave the registries where they started.
    for (let i = 0; i < 10; i++) {
      const path: Ref<string | null> = ref(`documents/doc-${i}.md`);
      const prefix: Ref<string | null> = ref('documents/');
      const wrapper = mount(harness(() => {
        useDocumentChangeReaction({ path, tryApply: () => 'applied' });
        useDocumentPrefixReaction({ prefix, onRemoteChange: () => {} });
      }));
      expect(total()).toBeGreaterThan(0);
      wrapper.unmount();
      expect(total()).toBe(0);
    }
  });
});
