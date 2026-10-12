// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';

import { createSwipeHistoryDetector, type WheelSample } from './swipeHistory';

/** Sequence-clock helper: every sample advances the clock by 10 ms. */
function detector(): ReturnType<typeof createSwipeHistoryDetector> {
  let t = 0;
  return createSwipeHistoryDetector(() => (t += 10));
}

const sample = (deltaX: number, deltaY = 0, extra?: Partial<WheelSample>): WheelSample => ({
  deltaX,
  deltaY,
  deltaMode: 0,
  defaultPrevented: false,
  ...extra,
});

const neverScrolls = (): boolean => false;
const alwaysScrolls = (): boolean => true;

describe('createSwipeHistoryDetector', () => {
  it('fires back on a leftward-to-right swipe (negative deltaX)', () => {
    const d = detector();
    // 250px threshold: 6 samples = 240px (null), the 7th = 280px fires.
    for (let i = 0; i < 6; i++) expect(d.feed(sample(-40), neverScrolls)).toBeNull();
    expect(d.feed(sample(-40), neverScrolls)).toBe('back');
  });

  it('fires forward on positive deltaX', () => {
    const d = detector();
    for (let i = 0; i < 6; i++) d.feed(sample(40), neverScrolls);
    expect(d.feed(sample(40), neverScrolls)).toBe('forward');
  });

  it('does not double-fire within one sequence', () => {
    const d = detector();
    const results: (string | null)[] = [];
    for (let i = 0; i < 20; i++) results.push(d.feed(sample(-40), neverScrolls));
    expect(results.filter((r) => r !== null)).toEqual(['back']); // exactly one
  });

  it('a new sequence after a pause fires again', () => {
    let t = 0;
    const d = createSwipeHistoryDetector(() => (t += 10));
    for (let i = 0; i < 6; i++) expect(d.feed(sample(-40), neverScrolls)).toBeNull();
    expect(d.feed(sample(-40), neverScrolls)).toBe('back');
    t += 500; // finger lifted, long pause
    expect(d.feed(sample(-40), neverScrolls)).toBeNull(); // fresh sequence (40px)
    for (let i = 0; i < 5; i++) d.feed(sample(-40), neverScrolls); // 240px
    expect(d.feed(sample(-40), neverScrolls)).toBe('back'); // 280px
  });

  it('vertical-dominant input is scrolling, not swiping', () => {
    const d = detector();
    for (let i = 0; i < 30; i++) d.feed(sample(10, 30), neverScrolls);
    expect(d.feed(sample(10, 30), neverScrolls)).toBeNull();
  });

  it('a preventDefaulted event (canvas panning) invalidates the sequence', () => {
    const d = detector();
    for (let i = 0; i < 5; i++) d.feed(sample(-40), neverScrolls);
    d.feed(sample(-40, 0, { defaultPrevented: true }), neverScrolls);
    // 200px accumulated pre-reset are gone: 6 fresh samples stay null.
    for (let i = 0; i < 6; i++) expect(d.feed(sample(-40), neverScrolls)).toBeNull();
    expect(d.feed(sample(-40), neverScrolls)).toBe('back'); // 280px since reset
  });

  it('a horizontally scrollable page keeps the swipe — blocked until the sequence ends', () => {
    const d = detector();
    for (let i = 0; i < 20; i++) d.feed(sample(-40), alwaysScrolls);
    // still in the same sequence: even after the page hits its scroll edge
    // the swipe does not fire retroactively.
    expect(d.feed(sample(-40), neverScrolls)).toBeNull();
  });

  it('blocked resets with the next sequence', () => {
    let t = 0;
    let scrolls = true;
    const d = createSwipeHistoryDetector(() => (t += 10));
    for (let i = 0; i < 20; i++) d.feed(sample(-40), () => scrolls);
    t += 500; // new sequence
    scrolls = false;
    for (let i = 0; i < 6; i++) d.feed(sample(-40), () => scrolls);
    expect(d.feed(sample(-40), () => scrolls)).toBe('back');
  });

  it('line-mode (mouse wheel) events never trigger navigation', () => {
    const d = detector();
    for (let i = 0; i < 20; i++) d.feed(sample(-40, 0, { deltaMode: 1 }), neverScrolls);
    expect(d.feed(sample(-40, 0, { deltaMode: 1 }), neverScrolls)).toBeNull();
  });
});
