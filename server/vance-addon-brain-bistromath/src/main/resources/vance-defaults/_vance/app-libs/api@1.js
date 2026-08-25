// api@1 — the REST layer over `vance.rest`.
//
// Requires nothing. An app that only talks to the API should not pull the
// document ergonomics of `core@1` into memory with it — that separation is why
// libraries are versioned and asked for by name rather than always present.
//
// What it adds over the raw call is three things, and each one is a mistake it
// stops being possible:
//   1. `projectId` — nearly every route wants it, and the program has it in
//      `vance.app.project`. Written by hand it is forgotten once and the route
//      answers about the wrong project, or 400s.
//   2. Branching on the status as a *number* (`e.status`), never on the wording
//      of a message.
//   3. `tryGet`, because for a lookup 404 is an answer and a thrown error is the
//      wrong shape for one.

const api = {

  /**
   * Any method. `projectId` is appended when the path does not already carry
   * one — pass your own to ask about another project (the reader's rights
   * still decide, as with every call).
   */
  call: async function (method, path, body) {
    return vance.rest(method, api.withProject(path), body);
  },

  /**
   * Any method, **without** the project parameter.
   *
   * For a route that is not project-scoped. The inbox is the case: a thread has
   * no project, so appending one would put a parameter nobody reads into every
   * request.
   */
  raw: function (method, path, body) {
    return vance.rest(method, path, body);
  },

  get: function (path) { return api.call('GET', path); },
  post: function (path, body) { return api.call('POST', path, body); },
  put: function (path, body) { return api.call('PUT', path, body); },
  patch: function (path, body) { return api.call('PATCH', path, body); },
  del: function (path) { return api.call('DELETE', path); },

  /**
   * A GET whose absence is an answer: `null` on 404, anything else still
   * throws.
   *
   * Deliberately only 404. A 403 also means "you get nothing", but the two
   * call for different code — one is "not there", the other "not yours" — and
   * folding them together is how a permission problem gets displayed as an
   * empty list for a week.
   */
  tryGet: async function (path) {
    try {
      return await api.get(path);
    } catch (e) {
      if (api.status(e) === 404) return null;
      throw e;
    }
  },

  /**
   * The HTTP status of a failed call, or `null`.
   *
   * `null` for anything the server never answered — a path the runtime refused,
   * a bad argument. Those are not 4xx and treating them as one would send a
   * program looking for a server problem that does not exist.
   */
  status: function (error) {
    return error && typeof error.status === 'number' ? error.status : null;
  },

  /** Add `projectId` unless the caller already named one. */
  withProject: function (path, projectId) {
    const p = String(path == null ? '' : path);
    if (/[?&]projectId=/.test(p)) return p;
    const id = projectId || (vance.app && vance.app.project);
    if (!id) return p;
    return p + (p.indexOf('?') < 0 ? '?' : '&') + 'projectId=' + encodeURIComponent(id);
  },

  /**
   * Build a query string from an object.
   *
   * Skips `null`/`undefined` — an absent filter and a filter for the empty
   * string are different requests, and spelling the first as the second is the
   * usual way a list comes back empty for no visible reason.
   */
  query: function (params) {
    const parts = [];
    for (const key of Object.keys(params || {})) {
      const v = params[key];
      if (v === null || v === undefined) continue;
      parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(v)));
    }
    return parts.join('&');
  },

  /** `api.path('documents/folder', {path: '/x', size: 20})` */
  path: function (base, params) {
    const q = api.query(params);
    if (!q) return base;
    return base + (String(base).indexOf('?') < 0 ? '?' : '&') + q;
  },

  // ── named routes ───────────────────────────────────────────────────
  //
  // Only where the raw call is genuinely awkward — a path plus three query
  // parameters in the right spelling. Not one wrapper per route: a list that
  // mirrors the whole API would go stale silently, and `api.get` already works
  // for everything.

  /**
   * The reader's own inbox threads.
   *
   * Through `raw`: a thread has no project, so these routes take no
   * `projectId`. Knowing that is precisely what a named wrapper is for.
   *
   * There is no `create` here because there is no `POST /inbox` — a thread is
   * opened by an agent (`inbox_post`) or as a discussion on a document. A
   * wrapper for a route that does not exist is worse than none: it fails at
   * runtime having looked plausible while it was written.
   */
  inbox: {
    /** Filters: `assignedTo`, `status`, `tag`, plus paging. */
    list: function (params) {
      return api.raw('GET', api.path('inbox', params));
    },
    count: function (params) {
      return api.raw('GET', api.path('inbox/count', params));
    },
    get: function (id) {
      return api.raw('GET', 'inbox/' + encodeURIComponent(id));
    },
    read: function (id) {
      return api.raw('POST', 'inbox/' + encodeURIComponent(id) + '/read');
    },
    archive: function (id) {
      return api.raw('POST', 'inbox/' + encodeURIComponent(id) + '/archive');
    },
  },

  /** Documents, for the cases `vance.documents.*` does not cover. */
  documents: {
    /**
     * A folder listing, unfiltered by the app's own root — `vance.documents.list`
     * resolves against the app folder, this does not.
     */
    folder: function (path, params) {
      const merged = Object.assign({ path: path, page: 0, size: 200 }, params || {});
      return api.get(api.path('documents/folder', merged));
    },
    search: function (params) {
      return api.get(api.path('documents/search', params));
    },
  },
};
