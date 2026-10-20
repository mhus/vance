import { describe, expect, it } from 'vitest';
import { useFrameHistory } from './frameHistory';

/**
 * What is worth asserting is not that the counts move but where they refuse
 * to: the buttons must never call `history.back()` at depth zero, because at
 * zero the step would leave the frame's history and navigate the whole app.
 * Every degradation the counting has (see frameHistory.ts) is allowed to err
 * towards "disabled" — these tests pin that direction.
 */
describe('useFrameHistory', () => {
  it('the initial load is entry zero — nothing to go back to', () => {
    const h = useFrameHistory();
    expect(h.canBack.value).toBe(false);

    // The frame mounting and loading its first document.
    expect(h.noteLoad()).toBe(true);
    expect(h.canBack.value).toBe(false);
    expect(h.canForward.value).toBe(false);
  });

  it('a navigation the reader did inside the frame enables back', () => {
    const h = useFrameHistory();
    h.noteLoad(); // initial
    h.noteLoad(); // the reader clicked a link in the frame

    expect(h.canBack.value).toBe(true);
    expect(h.canForward.value).toBe(false);
  });

  it('back is refused at zero — the step that would leave the app', () => {
    const h = useFrameHistory();
    h.noteLoad();

    expect(h.beginBack()).toBe(false);
  });

  it('our own back enables forward, and its load does not count as new', () => {
    const h = useFrameHistory();
    h.noteLoad(); // initial
    h.noteLoad(); // reader navigated once
    expect(h.beginBack()).toBe(true);
    expect(h.canBack.value).toBe(false);
    expect(h.canForward.value).toBe(true);

    // The load event the back step caused: ours, so not a new entry.
    expect(h.noteLoad()).toBe(true);
    expect(h.canForward.value).toBe(true);

    expect(h.beginForward()).toBe(true);
    expect(h.canForward.value).toBe(false);
    expect(h.canBack.value).toBe(true);
  });

  it('a fresh navigation after our back clears the forward stack', () => {
    // Real browser semantics: navigating forward into new territory drops
    // the entries that were behind the current position.
    const h = useFrameHistory();
    h.noteLoad(); // initial
    h.noteLoad(); // reader navigated
    h.beginBack();
    h.noteLoad(); // the load the back step caused — ours, not a new entry

    h.noteLoad(); // the reader clicks another link in the frame

    expect(h.canForward.value).toBe(false);
    expect(h.canBack.value).toBe(true);
  });

  it('a reload keeps the position but drops the forward stack', () => {
    const h = useFrameHistory();
    h.noteLoad(); // initial
    h.noteLoad(); // reader navigated
    h.beginBack(); // now at the frame’s first page
    h.noteLoad(); // the load the back step caused

    h.beginReload();

    expect(h.canForward.value).toBe(false);
    // A reload at the first page leaves nothing before it that belongs to
    // the frame — the step before it is the app itself.
    expect(h.canBack.value).toBe(false);
  });

  it('forward is refused when nothing was stepped back', () => {
    const h = useFrameHistory();
    h.noteLoad();
    h.noteLoad();

    expect(h.beginForward()).toBe(false);
  });
});
