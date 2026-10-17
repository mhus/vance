// @vitest-environment jsdom
//
// VRecipientInput lives in @vance/components, which has no test harness of
// its own; it is exercised here through the package entry like any host.
//
// What is pinned: the debounce (one lookup per pause, not per keystroke),
// the stale guard (a slow answer to an older query, or one arriving after a
// commit/choice/Escape/blur, never repopulates the list), the immediate
// commit on blur (a "Send" click right after typing must see the address),
// and that Escape on an open list does not reach a surrounding dialog.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import { nextTick } from 'vue';
import type { FormSuggestionDto } from '@vance/generated';
import { VRecipientInput } from '@vance/components';

function suggestion(label: string, ...values: string[]): FormSuggestionDto {
  return { label, values, kind: 'user' } as FormSuggestionDto;
}

function deferred<T>(): { promise: Promise<T>; resolve: (v: T) => void } {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function mountInput(fetchSuggestions?: (q: string) => Promise<FormSuggestionDto[]>): VueWrapper<any> {
  const wrapper = mount(VRecipientInput, {
    props: {
      modelValue: '',
      label: 'To',
      fetchSuggestions,
      'onUpdate:modelValue': (v: string) => wrapper.setProps({ modelValue: v }),
    },
    attachTo: document.body,
  });
  return wrapper;
}

async function type(wrapper: VueWrapper, text: string): Promise<void> {
  await wrapper.find('input').setValue(text);
}

describe('VRecipientInput', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('debounces lookups to one request per pause', async () => {
    const fetcher = vi.fn().mockResolvedValue([suggestion('Mara', 'mara@x.org')]);
    const wrapper = mountInput(fetcher);

    await type(wrapper, 'ma');
    vi.advanceTimersByTime(100);
    await type(wrapper, 'mar');
    vi.advanceTimersByTime(100);
    await type(wrapper, 'mara');
    expect(fetcher).not.toHaveBeenCalled();

    vi.advanceTimersByTime(200);
    await flush();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledWith('mara');
    expect(wrapper.findAll('li[role="option"]')).toHaveLength(1);
  });

  it('drops a slow answer to an older query', async () => {
    const first = deferred<FormSuggestionDto[]>();
    const second = deferred<FormSuggestionDto[]>();
    const fetcher = vi.fn()
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const wrapper = mountInput(fetcher);

    await type(wrapper, 'ma');
    vi.advanceTimersByTime(200);
    await type(wrapper, 'mo');
    vi.advanceTimersByTime(200);
    second.resolve([suggestion('Moritz', 'moritz@x.org')]);
    await flush();
    first.resolve([suggestion('Mara', 'mara@x.org')]);
    await flush();

    const options = wrapper.findAll('li[role="option"]');
    expect(options).toHaveLength(1);
    expect(options[0].text()).toContain('Moritz');
  });

  it('cancels a pending lookup when the query gets too short', async () => {
    const fetcher = vi.fn().mockResolvedValue([suggestion('Mara', 'mara@x.org')]);
    const wrapper = mountInput(fetcher);

    await type(wrapper, 'ma');
    await type(wrapper, 'm');
    vi.advanceTimersByTime(500);
    await flush();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it('ignores an in-flight answer after Escape', async () => {
    const pending = deferred<FormSuggestionDto[]>();
    const fetcher = vi.fn().mockReturnValue(pending.promise);
    const wrapper = mountInput(fetcher);

    await type(wrapper, 'ma');
    vi.advanceTimersByTime(200);
    await wrapper.find('input').trigger('keydown', { key: 'Escape' });
    pending.resolve([suggestion('Mara', 'mara@x.org')]);
    await flush();
    expect(wrapper.find('ul').exists()).toBe(false);
  });

  it('commits the typed address on blur without waiting', async () => {
    const wrapper = mountInput();
    await type(wrapper, 'mara@x.org');
    await wrapper.find('input').trigger('blur');
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['mara@x.org']);
  });

  it('does not reopen the list when an answer lands after blur', async () => {
    const pending = deferred<FormSuggestionDto[]>();
    const fetcher = vi.fn().mockReturnValue(pending.promise);
    const wrapper = mountInput(fetcher);

    await type(wrapper, 'ma');
    vi.advanceTimersByTime(200);
    await wrapper.find('input').trigger('blur');
    pending.resolve([suggestion('Mara', 'mara@x.org')]);
    await flush();
    expect(wrapper.find('ul').exists()).toBe(false);
  });

  it('keeps Escape from reaching the dialog while the list is open', async () => {
    const fetcher = vi.fn().mockResolvedValue([suggestion('Mara', 'mara@x.org')]);
    const wrapper = mountInput(fetcher);
    const outer = vi.fn();
    document.body.addEventListener('keydown', outer);

    await type(wrapper, 'ma');
    vi.advanceTimersByTime(200);
    await flush();
    expect(wrapper.find('ul').exists()).toBe(true);

    await wrapper.find('input').trigger('keydown', { key: 'Escape' });
    expect(outer).not.toHaveBeenCalled();
    expect(wrapper.find('ul').exists()).toBe(false);

    await wrapper.find('input').trigger('keydown', { key: 'Escape' });
    expect(outer).toHaveBeenCalledTimes(1);
    document.body.removeEventListener('keydown', outer);
  });

  it('commits on Tab without holding focus', async () => {
    const wrapper = mountInput();
    await type(wrapper, 'mara@x.org');
    const event = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true, bubbles: true });
    wrapper.find('input').element.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['mara@x.org']);
  });

  it('wires the combobox roles to the list and the active option', async () => {
    const fetcher = vi.fn().mockResolvedValue([
      suggestion('Mara', 'mara@x.org'),
      suggestion('Moritz', 'moritz@x.org'),
    ]);
    const wrapper = mountInput(fetcher);
    const input = wrapper.find('input');
    expect(input.attributes('role')).toBe('combobox');
    expect(input.attributes('aria-expanded')).toBe('false');
    const labelId = wrapper.find('.v-field-label').attributes('id');
    expect(input.attributes('aria-labelledby')).toBe(labelId);

    await type(wrapper, 'mo');
    vi.advanceTimersByTime(200);
    await flush();
    await input.trigger('keydown', { key: 'ArrowDown' });
    await input.trigger('keydown', { key: 'ArrowDown' });

    const list = wrapper.find('ul');
    expect(input.attributes('aria-expanded')).toBe('true');
    expect(input.attributes('aria-controls')).toBe(list.attributes('id'));
    const options = wrapper.findAll('li[role="option"]');
    expect(input.attributes('aria-activedescendant')).toBe(options[1].attributes('id'));
    expect(options[1].attributes('aria-selected')).toBe('true');
  });
});
