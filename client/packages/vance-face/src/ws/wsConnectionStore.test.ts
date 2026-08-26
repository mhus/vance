import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * Leak guard for the tab-singleton's registries.
 *
 * <p>This exists because of the workspace cluster: cortex, chat, inbox and
 * documents are routes now, so leaving one unmounts a component instead of
 * destroying the JS realm. In the old multi-page build a forgotten
 * unsubscribe was invisible — the browser swept it up on every navigation. A
 * session now lasts a working day, and a forgotten handler lasts with it.
 *
 * <p>What is checked is the property that makes that safe: every registration
 * hands back an unregister, and calling it puts the store back exactly where
 * it started. A component that keeps the returned function and calls it in
 * `onUnmounted` therefore leaves nothing behind.
 *
 * <p>The store touches no browser API at module scope, so it imports cleanly
 * in a Node run — no DOM, no mounting, no component harness. The `subscribe*`
 * functions are deliberately NOT exercised: those go to the wire, and a
 * unit test that needs a socket is a test about the socket.
 */

// The store reaches for the REST config and the session cookie when it opens a
// connection. Nothing here opens one, but the module-level import pulls both
// in — stub them so the import is side-effect free in Node.
vi.mock('@vance/shared', () => ({
  BrainWebSocket: { connect: vi.fn() },
  getRestConfig: () => ({ baseUrl: '' }),
  getTenantId: () => 'acme',
  setActiveSessionId: vi.fn(),
  WebSocketRequestError: class extends Error {},
}));
vi.mock('@/platform/webUiSession', () => ({ getSessionData: () => null }));

import {
  onClientOutput,
  onClientPrompt,
  onClientRoster,
  onClientState,
  onDocumentChanged,
  onDocumentChangedPrefix,
  onDocumentNoteChanged,
  onDocumentPrefixReconnect,
  onPointer,
  onPointerLeave,
  onSignal,
  subscriptionCounts,
} from './wsConnectionStore';

/** Every listener registration, as (name, register) pairs. */
const REGISTRATIONS: ReadonlyArray<[string, () => () => void]> = [
  ['onDocumentChanged', () => onDocumentChanged('documents/a.md', vi.fn())],
  ['onDocumentChangedPrefix', () => onDocumentChangedPrefix('documents/', vi.fn())],
  ['onDocumentPrefixReconnect', () => onDocumentPrefixReconnect('documents/', vi.fn())],
  ['onDocumentNoteChanged', () => onDocumentNoteChanged('documents/a.md', vi.fn())],
  ['onPointer', () => onPointer('documents/a.md', vi.fn())],
  ['onPointerLeave', () => onPointerLeave('documents/a.md', vi.fn())],
  ['onSignal', () => onSignal('documents/a.md', vi.fn())],
  ['onClientRoster', () => onClientRoster(vi.fn())],
  ['onClientOutput', () => onClientOutput('client-1', vi.fn())],
  ['onClientState', () => onClientState('client-1', vi.fn())],
  ['onClientPrompt', () => onClientPrompt('client-1', vi.fn())],
];

function total(): number {
  return Object.values(subscriptionCounts()).reduce((a, b) => a + b, 0);
}

describe('wsConnectionStore registries', () => {
  beforeEach(() => {
    // Nothing to reset: each case unregisters what it registers, which is the
    // property under test. A leaking case therefore fails the NEXT one too,
    // which is the right amount of noise for a leak.
    expect(total()).toBe(0);
  });

  it.each(REGISTRATIONS)('%s puts the store back where it was', (_name, register) => {
    const before = subscriptionCounts();
    const off = register();
    expect(total()).toBe(Object.values(before).reduce((a, b) => a + b, 0) + 1);
    off();
    expect(subscriptionCounts()).toEqual(before);
  });

  it('a full round of every listener leaves nothing behind', () => {
    // The route-cycle case in miniature: one editor's worth of registrations
    // goes on, then all of it comes off.
    const offs = REGISTRATIONS.map(([, register]) => register());
    expect(total()).toBe(REGISTRATIONS.length);

    offs.forEach((off) => off());

    expect(total()).toBe(0);
  });

  it('unregistering twice is harmless', () => {
    // `onUnmounted` can run after an explicit cleanup — a component that
    // handles both must not corrupt the counts.
    const off = onDocumentChanged('documents/a.md', vi.fn());
    off();
    off();
    expect(total()).toBe(0);
  });

  it('two handlers on the same path unregister independently', () => {
    // The case a naive `delete(path)` gets wrong: two panels watch the same
    // document, one closes, and the other stops receiving changes.
    const first = onDocumentChanged('documents/a.md', vi.fn());
    const second = onDocumentChanged('documents/a.md', vi.fn());
    expect(subscriptionCounts().documentChangedListeners).toBe(2);

    first();

    expect(subscriptionCounts().documentChangedListeners).toBe(1);
    second();
    expect(total()).toBe(0);
  });
});
