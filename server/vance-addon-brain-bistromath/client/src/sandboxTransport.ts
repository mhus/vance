/**
 * How the host reaches the guest — the one piece of the sandbox that knows
 * *where* the program runs.
 *
 * <p>Everything above this (the protocol, the queue, the watchdog, the
 * lifecycle hooks) is engine-agnostic. That split exists for two reasons and
 * both are load-bearing:
 *
 * <ul>
 *   <li><b>The engine will change.</b> The spec argues QuickJS-WASM; this build
 *       uses a null-origin iframe. When the foundation library makes module
 *       imports necessary, only this file is replaced — no app document, and no
 *       line of the protocol, knows the difference.</li>
 *   <li><b>The bugs were above the line.</b> All four defects found while
 *       building — a watchdog on elapsed time, a click overtaking `init`, hooks
 *       detected from an error string, a teardown cutting work off — lived in
 *       the protocol, not in the frame. A seam here is what lets that half be
 *       tested without a browser.</li>
 * </ul>
 */

/** What the sandbox needs from wherever the program runs. */
export interface GuestTransport {
  /** Bring the guest up and route its messages to `onMessage`. */
  start(onMessage: (message: unknown) => void): void;
  /** Send one message to the guest. A no-op once disposed. */
  post(message: unknown): void;
  /** Take the guest down, with everything it was holding. */
  dispose(): void;
}

/**
 * The guest bootstrap. Fixed text, so the program source never has to be
 * HTML-escaped into `srcdoc` — it arrives afterwards by message.
 *
 * <p>Two details are load-bearing. `(0, eval)` is *indirect* eval, which runs
 * in global scope: a top-level `function hello()` in the program therefore
 * becomes reachable by name, which is what lets a view say `main.js:hello`
 * without the program exporting anything. And every host call is a promise
 * resolved by message id, because everything across this boundary is async.
 */
export const GUEST_BOOTSTRAP = `<!doctype html><meta charset="utf-8"><script>
(function () {
  var pending = {}, nextId = 1;
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = 'c' + (nextId++);
      pending[id] = { resolve: resolve, reject: reject };
      parent.postMessage({ t: 'call', id: id, method: method, args: args }, '*');
    });
  }
  window.vance = {
    state: {
      set: function (k, v) { return call('state.set', [k, v]); },
      get: function (k) { return call('state.get', [k]); }
    },
    documents: {
      list: function (p) { return call('documents.list', [p]); },
      read: function (p) { return call('documents.read', [p]); },
      write: function (p, c, o) { return call('documents.write', [p, c, o]); },
      create: function (p, c) { return call('documents.create', [p, c]); },
      delete: function (p) { return call('documents.delete', [p]); }
    },
    ui: {
      notify: function (t, s) { return call('ui.notify', [t, s]); },
      show: function (h) { return call('ui.show', [h]); }
    }
  };
  function reply(id, error, value) {
    parent.postMessage({ t: 'done', id: id, error: error || null, value: value }, '*');
  }
  function settle(id, result) {
    if (result && typeof result.then === 'function') {
      result.then(function (v) { reply(id, null, v); }, function (e) { reply(id, String(e)); });
    } else {
      reply(id, null, result);
    }
  }
  window.addEventListener('message', function (ev) {
    if (ev.source !== parent) return;
    var m = ev.data;
    if (!m || typeof m !== 'object') return;
    if (m.t === 'result') {
      var p = pending[m.id];
      if (!p) return;
      delete pending[m.id];
      if (m.ok) p.resolve(m.value); else p.reject(new Error(m.message));
      return;
    }
    if (m.t === 'eval') {
      try { (0, eval)(m.code); reply(m.id); }
      catch (e) { reply(m.id, String(e)); }
      return;
    }
    if (m.t === 'has') {
      var out = {};
      for (var i = 0; i < m.names.length; i++) {
        out[m.names[i]] = typeof window[m.names[i]] === 'function';
      }
      reply(m.id, null, out);
      return;
    }
    if (m.t === 'invoke') {
      var fn = window[m.fn];
      if (typeof fn !== 'function') { reply(m.id, 'no function named ' + m.fn); return; }
      try { settle(m.id, fn.apply(null, m.args || [])); }
      catch (e) { reply(m.id, String(e)); }
      return;
    }
  });
  parent.postMessage({ t: 'ready' }, '*');
})();
<\/script>`;

/**
 * The program in a hidden `<iframe sandbox="allow-scripts">` with `srcdoc`.
 *
 * <p>That attribute set gives the frame an **opaque origin**: no
 * `localStorage` (access throws), no cookies, no access to the parent DOM, and
 * no `fetch` carrying our session. `allow-same-origin` is deliberately absent —
 * adding it would quietly turn this into "eval in the page realm with extra
 * steps".
 */
export class IframeTransport implements GuestTransport {
  private frame: HTMLIFrameElement | null = null;
  private listener: ((ev: MessageEvent) => void) | null = null;

  start(onMessage: (message: unknown) => void): void {
    const frame = document.createElement('iframe');
    frame.setAttribute('sandbox', 'allow-scripts');
    frame.setAttribute('aria-hidden', 'true');
    frame.style.display = 'none';
    frame.srcdoc = GUEST_BOOTSTRAP;

    this.listener = (ev: MessageEvent) => {
      // Identity by window, not by origin: a null-origin frame reports "null",
      // which any other sandboxed frame on the page would report too.
      if (!this.frame || ev.source !== this.frame.contentWindow) return;
      onMessage(ev.data);
    };
    window.addEventListener('message', this.listener);
    document.body.appendChild(frame);
    this.frame = frame;
  }

  post(message: unknown): void {
    this.frame?.contentWindow?.postMessage(message, '*');
  }

  dispose(): void {
    if (this.listener) window.removeEventListener('message', this.listener);
    this.listener = null;
    // Removing the frame takes its timers, listeners and heap with it — which
    // is why the sandbox above needs no registry of what the program started.
    this.frame?.remove();
    this.frame = null;
  }
}
