// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

/**
 * The lazy-KaTeX path in MarkdownView.
 *
 * <p>KaTeX is 252 KB and MarkdownView sits in the shared components barrel, so
 * a static import put a LaTeX engine on every page — including ones that can
 * never render a formula. It is fetched on first sight of a math token
 * instead. The awkward part, and the reason this needs a test rather than a
 * measurement: `marked`'s renderer is **synchronous**, so the first pass
 * cannot await the import. It emits the formula's own source, and a one-shot
 * reactive bump re-renders the same node once the engine is in.
 *
 * <p>Both halves matter and only the second is obvious. If the placeholder
 * regressed, a reader would see a gap or raw markup where a formula belongs;
 * if the bump regressed, the source would simply never turn into math — and
 * nothing would throw either way. The build cannot see this at all: it is a
 * render that changes after a promise resolves.
 */

vi.mock('@/platform/webUiSession', () => ({ getSessionData: () => null }));

import MarkdownView from './MarkdownView.vue';

const FORMULA = String.raw`$$\frac{1}{2}$$`;

function render(source: string) {
  return mount(MarkdownView, { props: { source } });
}

describe('MarkdownView math rendering', () => {
  // The component resolves `vance:` links through a Pinia store. Not what is
  // under test here, but it is reached during setup, so it has to exist.
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('shows the formula source before the engine has loaded', () => {
    const wrapper = render(FORMULA);

    // Synchronous first pass: no await, so the dynamic import cannot have
    // settled. The reader sees what the author typed, not an empty box.
    const pending = wrapper.find('.katex-pending');
    expect(pending.exists()).toBe(true);
    expect(pending.text()).toContain('\\frac{1}{2}');
    expect(wrapper.find('.katex').exists()).toBe(false);
  });

  it('replaces the source with rendered math once the engine arrives', async () => {
    const wrapper = render(FORMULA);

    // Polled, not flushed: loading KaTeX is a real module load, so a single
    // microtask drain is not enough and a fixed sleep would be a guess.
    await vi.waitFor(async () => {
      await wrapper.vm.$nextTick();
      expect(wrapper.find('.katex-pending').exists()).toBe(false);
    });

    // KaTeX emits its own markup; the placeholder being gone is only possible
    // if the epoch bump actually re-evaluated the computed.
    expect(wrapper.html()).toContain('katex');
  });

  it('escapes the placeholder, because a formula is untrusted text', async () => {
    // The body is authored content — an LLM turn, a shared document. The
    // pending branch writes into innerHTML via the sanitiser, and while the
    // sanitiser would catch this, the escape is what keeps the two independent.
    const wrapper = render(String.raw`$$<img src=x onerror=alert(1)>$$`);

    // The payload must survive as TEXT, not as an element. Asserting on the
    // absence of the substring would be wrong: `onerror=` legitimately appears
    // in the escaped text, and a test that forbids it would fail on correct
    // output.
    expect(wrapper.find('img').exists()).toBe(false);
    expect(wrapper.html()).toContain('&lt;img');
  });

  it('leaves a document without math alone', async () => {
    // The common case, and the one that must not pay for any of the above.
    const wrapper = render('# Title\n\nJust prose.\n');

    await flushPromises();

    expect(wrapper.find('.katex-pending').exists()).toBe(false);
    expect(wrapper.text()).toContain('Just prose.');
  });
});
