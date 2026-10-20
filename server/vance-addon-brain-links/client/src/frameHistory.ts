import { computed, ref } from 'vue';

/**
 * Navigation bookkeeping for a frame that shows a cross-origin page.
 *
 * <p><b>Why this exists.</b> Back and forward for a foreign frame have no
 * real API: `contentWindow.history` is blocked cross-origin, and the one
 * working mechanism is the joint session history — every navigation inside
 * the frame becomes an entry of the top page's history, so
 * `window.history.back()` steps the <em>frame</em> back while the app stays
 * put (verified against Chrome). The price is that the frame's history depth
 * is not queryable, so the buttons have to count what they saw: every `load`
 * the frame fires that our own back/forward/reload did not cause is one
 * more step to go back through.
 *
 * <p>The counting errs deliberately on the safe side. Same-document
 * navigations inside the frame (pushState, anchors) add history entries but
 * fire no `load` — the buttons stay disabled for those, and the reader falls
 * back to the browser's own back gesture, which was always going to work.
 * The opposite direction (a `load` without a new entry, e.g. a site calling
 * its own `history.back()`) can overcount; the guard that matters is that the
 * buttons never call `history.back()` at depth zero, because at zero the
 * step would leave the frame's own history and navigate the whole app.
 */
export function useFrameHistory() {
  /** Entries the frame owns beyond its first document. */
  const depth = ref(0);
  /** Steps that can be taken again after our own back. */
  const forward = ref(0);
  /** Which navigation our own chrome started and is still waiting for. */
  const pending = ref<'back' | 'forward' | 'reload' | null>(null);
  /** Whether the frame's initial document has been seen. */
  let sawInitial = false;

  const canBack = computed(() => depth.value > 0);
  const canForward = computed(() => forward.value > 0);

  /**
   * A `load` the frame fired. Returns true when it belongs to a navigation
   * this controller started — the caller must not count its own steps.
   */
  function noteLoad(): boolean {
    // The initial document is entry zero of the frame's own history — there
    // is nothing before it that belongs to the frame, and one step before it
    // is the app itself.
    if (!sawInitial) {
      sawInitial = true;
      return true;
    }
    const self = pending.value !== null;
    pending.value = null;
    if (!self) {
      depth.value += 1;
      forward.value = 0;
    }
    return self;
  }

  /** Mark a back step as started. False when there is nothing to go back to. */
  function beginBack(): boolean {
    if (depth.value === 0) return false;
    depth.value -= 1;
    forward.value += 1;
    pending.value = 'back';
    return true;
  }

  /** Mark a forward step as started. False when there is no step to take. */
  function beginForward(): boolean {
    if (forward.value === 0) return false;
    forward.value -= 1;
    depth.value += 1;
    pending.value = 'forward';
    return true;
  }

  /**
   * Mark a reload as started. Keeps the position, drops the forward stack —
   * a `location.replace` starts a new document where the reader stands.
   */
  function beginReload(): void {
    forward.value = 0;
    pending.value = 'reload';
  }

  return { canBack, canForward, beginBack, beginForward, beginReload, noteLoad };
}
