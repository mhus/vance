/**
 * Two-finger trackpad swipe = back/forward page navigation
 * (planning/desktop-profile-tabs.md — browser parity for the desktop
 * app's tabs).
 *
 * <p>Why here and not in the app: Electron has NO native two-finger
 * history navigation — the Chromium content-layer overscroll feature the
 * `OverscrollHistoryNavigation` switch once enabled is REMOVED from the
 * binary (verified against Electron 44's framework: zero hits), modern
 * Chrome handles the gesture in its mac-only chrome/-layer history swiper,
 * which Electron does not ship, and the Electron PR porting that swiper
 * sits unmerged. What the trackpad DOES emit on default system settings is
 * wheel events with `deltaX` — the same input path Chrome's own detection
 * starts from. This module classifies those.
 *
 * <p><b>Desktop app only</b> ({@link getDesktopTools} present): in a real
 * browser the native swipe stays native — installing this there would
 * double-fire on Chrome's own history navigation.
 *
 * <p><b>Consumption rules</b> — a swipe must not steal from page content:
 * a `preventDefault`ed wheel event (canvas panning, Scribble) invalidates
 * the sequence, and a horizontally scrollable ancestor (Sheets, code) under
 * the pointer blocks navigation in the direction it can still scroll. A
 * vertical-dominant sequence is scrolling, not swiping.
 */
import { getDesktopTools } from '@vance/shared';

/** Wheel deltas are pixel-based; line/page modes are mouse wheels. */
const PIXEL_DELTA_MODE = 0;
/** Accumulated |deltaX| that completes a swipe (Chrome's own threshold is
 * in the same ballpark — deliberately high enough that horizontal content
 * scrolling rarely reaches it by accident, low enough for one deliberate
 * swipe). */
const SWIPE_THRESHOLD_PX = 250;
/** No wheel event for this long = the sequence ended (finger lifted; the
 * next event starts a fresh swipe). */
const SEQUENCE_GAP_MS = 150;
/** |sumX| must beat |sumY| by this factor — a diagonal is scrolling. */
const HORIZONTAL_DOMINANCE = 2;

export interface WheelSample {
  deltaX: number;
  deltaY: number;
  deltaMode: number;
  /** Another handler already claimed the event — the page owns it. */
  defaultPrevented: boolean;
}

/**
 * Classifies a wheel-event stream into swipe navigation. Pure except the
 * clock (injectable for tests); the DOM checks are the installer's via
 * the `canScrollHorizontally` callback, so the detector is unit-testable
 * without a page.
 */
export function createSwipeHistoryDetector(
  now: () => number = () => performance.now(),
): {
  feed(
    sample: WheelSample,
    canScrollHorizontally: (direction: -1 | 1) => boolean,
  ): 'back' | 'forward' | null;
} {
  let lastTime = Number.NEGATIVE_INFINITY;
  let sumX = 0;
  let sumY = 0;
  let fired = false;
  let blocked = false;

  const reset = (): void => {
    sumX = 0;
    sumY = 0;
    fired = false;
    blocked = false;
  };

  return {
    feed(sample, canScrollHorizontally) {
      if (sample.deltaMode !== PIXEL_DELTA_MODE) {
        reset();
        return null;
      }
      if (sample.defaultPrevented) {
        reset();
        return null;
      }
      const t = now();
      if (t - lastTime > SEQUENCE_GAP_MS) reset();
      lastTime = t;
      sumX += sample.deltaX;
      sumY += sample.deltaY;
      if (Math.abs(sumY) * HORIZONTAL_DOMINANCE > Math.abs(sumX)) {
        // vertical-dominant — scrolling, not swiping
        reset();
        return null;
      }
      if (Math.abs(sumX) < SWIPE_THRESHOLD_PX || fired || blocked) return null;
      // Fingers moving right drag the previous page in = back; the wheel
      // reports that as negative deltaX (content follows the fingers).
      const direction: -1 | 1 = sumX < 0 ? -1 : 1;
      if (canScrollHorizontally(direction)) {
        // the page still has somewhere to scroll in that direction —
        // stay blocked until the sequence ends, however long they scroll
        blocked = true;
        return null;
      }
      fired = true;
      return sumX < 0 ? 'back' : 'forward';
    },
  };
}

/**
 * Whether the element (or an ancestor) under the swipe can still scroll
 * horizontally in `direction` — if so the swipe belongs to the content.
 */
function canScrollHorizontally(target: Element | null, direction: -1 | 1): boolean {
  let el: Element | null = target;
  while (el !== null && el !== document.documentElement) {
    if (el.scrollWidth > el.clientWidth && el.clientWidth > 0) {
      const canLeft = el.scrollLeft > 0;
      const canRight = el.scrollLeft + el.clientWidth < el.scrollWidth;
      if ((direction === -1 && canLeft) || (direction === 1 && canRight)) return true;
    }
    el = el.parentElement;
  }
  // Document-level horizontal overflow (rare, but a wide canvas page can
  // produce it) — the same rule at the root.
  const de = document.documentElement;
  if (de !== null && de.scrollWidth > de.clientWidth) {
    const canLeft = window.scrollX > 0;
    const canRight = window.scrollX + window.innerWidth < de.scrollWidth;
    if ((direction === -1 && canLeft) || (direction === 1 && canRight)) return true;
  }
  return false;
}

/** Install the swipe navigation for this page. No-op outside the desktop
 *  app (see the module docs) — call once from the web boot. */
export function installSwipeHistory(): void {
  if (getDesktopTools() === null) return;
  const detector = createSwipeHistoryDetector();
  document.addEventListener(
    'wheel',
    (event) => {
      const target = event.target instanceof Element ? event.target : null;
      const action = detector.feed(
        {
          deltaX: event.deltaX,
          deltaY: event.deltaY,
          deltaMode: event.deltaMode,
          defaultPrevented: event.defaultPrevented,
        },
        (direction) => canScrollHorizontally(target, direction),
      );
      if (action === 'back') window.history.back();
      if (action === 'forward') window.history.forward();
    },
    // capture: see every wheel event; passive: we never preventDefault —
    // the page keeps full control over its own scrolling.
    { capture: true, passive: true },
  );
}
