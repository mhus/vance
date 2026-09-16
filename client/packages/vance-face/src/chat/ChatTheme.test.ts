// @vitest-environment jsdom
//
// The component's contract is the fail-open fetch + the style
// injection: the served CSS becomes a <style> element in the light
// DOM (scoped server-side to `.chat-theme`; the scope root itself is
// the transcript scroll container, bound by ChatView), and every
// failure degrades to "no theme", never to an error state.
// `brainFetchText` is mocked so each case is decided by the seam,
// not the network.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const brainFetchText = vi.fn();

vi.mock('@vance/shared', () => ({
  brainFetchText: (...args: unknown[]) => brainFetchText(...args),
}));

import ChatTheme from './ChatTheme.vue';

const SLOT_HTML = '<p class="transcript-slot">hi</p>';

function mountFrame(themeName: string | null, projectId: string | null) {
  return mount(ChatTheme, {
    props: { themeName, projectId },
    slots: { default: SLOT_HTML },
  });
}

describe('ChatTheme', () => {
  beforeEach(() => {
    brainFetchText.mockReset();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('injects the fetched css as a style element before the transcript', async () => {
    brainFetchText.mockResolvedValue('.chat-theme.chat-theme h1 { color: red; }');

    const wrapper = mountFrame('acme', 'proj-1');
    await vi.waitUntil(() => wrapper.find('style').exists());

    expect(wrapper.find('style').text()).toContain('.chat-theme.chat-theme h1');
    // The fetch path mirrors the REST route: project first (the theme
    // cascade is project-local), then the theme name.
    expect(brainFetchText).toHaveBeenCalledWith('projects/proj-1/chat-themes/acme/css');
    // Fragment rendering: style element + slot, no wrapper element of
    // its own — the scope root belongs to ChatView's scroll container.
    expect(wrapper.find('.transcript-slot').exists()).toBe(true);
    expect(wrapper.element.children.length).toBe(2);
  });

  it('fetch failure renders no style and keeps the transcript (fail-open)', async () => {
    brainFetchText.mockRejectedValue(new Error('network down'));
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const wrapper = mountFrame('broken-theme', 'proj-1');
    await vi.waitUntil(() => warn.mock.calls.length > 0);

    expect(wrapper.find('style').exists()).toBe(false);
    // The transcript stays — a styling problem never blocks a render.
    expect(wrapper.find('.transcript-slot').exists()).toBe(true);
  });

  it('null theme or project skips the fetch entirely', () => {
    const wrapper = mountFrame(null, 'proj-1');
    expect(brainFetchText).not.toHaveBeenCalled();
    expect(wrapper.find('style').exists()).toBe(false);
    // The slot renders regardless — the frame is not conditional on
    // the theme.
    expect(wrapper.find('.transcript-slot').exists()).toBe(true);
  });

  it('serves a cached theme without a second request', async () => {
    brainFetchText.mockResolvedValue('.chat-theme.chat-theme h1 { color: red; }');

    const first = mountFrame('cached-theme', 'proj-1');
    await vi.waitUntil(() => first.find('style').exists());

    const second = mountFrame('cached-theme', 'proj-1');
    await vi.waitUntil(() => second.find('style').exists());

    expect(second.find('style').text()).toContain('.chat-theme.chat-theme h1');
    expect(brainFetchText).toHaveBeenCalledTimes(1);
  });

  it('a different project with the same name is a different cache entry', async () => {
    brainFetchText.mockResolvedValue('.chat-theme.chat-theme h1 { color: red; }');

    const first = mountFrame('scoped-theme', 'proj-1');
    await vi.waitUntil(() => first.find('style').exists());

    const otherProject = mountFrame('scoped-theme', 'proj-2');
    await vi.waitUntil(() => otherProject.find('style').exists());

    // Same name, different cascade layer — both asked, both cached.
    expect(brainFetchText).toHaveBeenCalledTimes(2);
  });
});
