// @vitest-environment jsdom
//
// The dialog mechanics, not the search backend: the route is the link
// picker's, already covered server-side. What is this component's own is
// the keyboard contract — selection stays in the search field, ↑/↓ wrap,
// Enter opens the selected row, and every fresh search lands on the first
// hit. Stale-answer discipline (sequence numbers) is exercised too: a slow
// earlier response must not overwrite a faster later one.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { DocumentSearchItem } from '@vance/generated';

const brainFetchMock = vi.fn();

vi.mock('@vance/shared', () => ({
  brainFetch: (...args: unknown[]) => brainFetchMock(...args),
}));

// The component imports the V* primitives through the `@/components` barrel —
// right in the app, but under vitest's standalone config that barrel drags
// in SessionHeader → `@composables`, an alias only the app's vite.config.ts
// knows. Mock the barrel with the real primitives it re-exports; the
// mechanics under test are exactly theirs.
vi.mock('@/components', async () => {
  const primitives = await vi.importActual<typeof import('@vance/components')>('@vance/components');
  return {
    VAlert: primitives.VAlert,
    VInput: primitives.VInput,
    VModal: primitives.VModal,
  };
});

import OpenDocumentModal from './OpenDocumentModal.vue';

const SELECTED = 'bg-primary/15';

function doc(id: string, path: string): DocumentSearchItem {
  return { id, path, title: path, kind: null, mimeType: null };
}

const DOCS: DocumentSearchItem[] = [
  doc('a', 'documents/a.md'),
  doc('b', 'documents/b.md'),
  doc('c', 'documents/c.md'),
];

async function mountOpen() {
  const wrapper = mount(OpenDocumentModal, {
    props: { open: true, projectId: 'p1' },
    attachTo: document.body,
    // `$t` passthrough: no assertion looks at the strings, only at what
    // the dialog does with the state behind them.
    global: { mocks: { $t: (key: string) => key } },
  });
  await flushPromises();
  return wrapper;
}

function input(wrapper: ReturnType<typeof mountOpen>) {
  return wrapper.find('input');
}

async function key(wrapper: ReturnType<typeof mountOpen>, key: string) {
  await input(wrapper).trigger('keydown', { key });
}

function selectedPath(wrapper: ReturnType<typeof mountOpen>): string {
  const li = wrapper.findAll('li').find((el) => el.classes().includes(SELECTED));
  return li?.find('span.opacity-60')?.text() ?? '(none)';
}

describe('OpenDocumentModal', () => {
  beforeEach(() => {
    brainFetchMock.mockReset();
    // jsdom has no layout engine — scrollIntoView is the one DOM API here
    // it cannot honour. Its *call* is part of the contract (the keyboard
    // must keep the highlight on screen), so stub rather than skip.
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    // Same for the dialog's top-layer machinery: jsdom parses <dialog> but
    // implements neither showModal() nor close(). The modal's own logic
    // (which of those it calls, when) is what runs under test; the browser's
    // paint layer is not ours to verify anyway.
    window.HTMLDialogElement.prototype.showModal = vi.fn();
    window.HTMLDialogElement.prototype.close = vi.fn();
  });

  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('searches on open and selects the first result', async () => {
    brainFetchMock.mockResolvedValue({ items: DOCS, total: 3 });

    const wrapper = await mountOpen();

    expect(brainFetchMock).toHaveBeenCalledWith('GET', expect.stringContaining('size=100'));
    expect(wrapper.findAll('li')).toHaveLength(3);
    expect(selectedPath(wrapper)).toBe('documents/a.md');
  });

  it('moves the selection with arrow keys and wraps at both ends', async () => {
    brainFetchMock.mockResolvedValue({ items: DOCS, total: 3 });
    const wrapper = await mountOpen();

    await key(wrapper, 'ArrowDown');
    expect(selectedPath(wrapper)).toBe('documents/b.md');

    await key(wrapper, 'ArrowUp');
    expect(selectedPath(wrapper)).toBe('documents/a.md');

    await key(wrapper, 'ArrowUp');
    expect(selectedPath(wrapper)).toBe('documents/c.md');
  });

  it('opens the selected document on Enter and closes the dialog', async () => {
    brainFetchMock.mockResolvedValue({ items: DOCS, total: 3 });
    const wrapper = await mountOpen();

    await key(wrapper, 'ArrowDown');
    await key(wrapper, 'Enter');

    expect(wrapper.emitted('open')).toEqual([['b']]);
    expect(wrapper.emitted('update:open')).toEqual([[false]]);
  });

  it('re-searches debounced and lands on the first hit again', async () => {
    vi.useFakeTimers();
    try {
      brainFetchMock.mockResolvedValue({ items: DOCS, total: 3 });
      const wrapper = await mountOpen();
      await key(wrapper, 'ArrowDown');
      expect(selectedPath(wrapper)).toBe('documents/b.md');

      brainFetchMock.mockResolvedValue({ items: [doc('x', '_vance/x.yaml')], total: 1 });
      await input(wrapper).setValue('_vance/x.yaml');

      // Nothing before the debounce window…
      expect(brainFetchMock).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(200);
      await flushPromises();

      expect(brainFetchMock).toHaveBeenCalledTimes(2);
      expect(selectedPath(wrapper)).toBe('_vance/x.yaml');

      // …and Enter opens it — the paste-a-path flow end to end.
      await key(wrapper, 'Enter');
      expect(wrapper.emitted('open')).toEqual([['x']]);
    } finally {
      vi.useRealTimers();
    }
  });

  it('discards a stale response that lands after a newer one', async () => {
    vi.useFakeTimers();
    try {
      brainFetchMock.mockResolvedValueOnce({ items: DOCS, total: 3 });
      const wrapper = await mountOpen();

      // The answer to the first query stays pending on the wire…
      let late!: (v: { items: DocumentSearchItem[]; total: number }) => void;
      brainFetchMock.mockReturnValueOnce(
        new Promise((resolve) => {
          late = resolve;
        }),
      );
      await input(wrapper).setValue('slow');
      vi.advanceTimersByTime(200);
      await flushPromises();
      expect(brainFetchMock).toHaveBeenCalledTimes(2);

      // …while the second query answers fast.
      brainFetchMock.mockResolvedValueOnce({ items: [doc('new', 'new.md')], total: 1 });
      await input(wrapper).setValue('new2');
      vi.advanceTimersByTime(200);
      await flushPromises();
      expect(selectedPath(wrapper)).toBe('new.md');

      // Now the stale answer arrives — it must not overwrite the fresh one.
      late({ items: [doc('old', 'old.md')], total: 1 });
      await flushPromises();
      expect(selectedPath(wrapper)).toBe('new.md');
    } finally {
      vi.useRealTimers();
    }
  });

  it('reports a failed search instead of an empty result list', async () => {
    brainFetchMock.mockRejectedValue(new Error('boom'));

    const wrapper = await mountOpen();

    expect(wrapper.text()).toContain('boom');
    expect(wrapper.findAll('li')).toHaveLength(0);
  });
});
