// core@1 — the ergonomics layer over `vance.*`.
//
// Everything here is something that was written by hand in every app before it
// existed. Nothing here does anything `vance.*` cannot: `core` is shorter, not
// more powerful, and a program that never loads it is not missing a capability.
//
// Three rules kept it small:
//   1. In this library only if it was repeated, or is easy to get subtly wrong
//      (paging is zero-based, sorting has to be stable, an empty cell is not
//      the smallest value).
//   2. No policy. No currency, no locale, no date format, no page size default
//      that pretends to know your data. `core.num` formats a number; what a
//      number *means* is the app's business.
//   3. Nothing that hides an error. A read that fails throws where it failed.

const core = {

  // ── documents ──────────────────────────────────────────────────────

  /**
   * A folder of documents as an array of records — the "folder as a table"
   * convention, which is four lines in every app that has data.
   *
   * Each record is the document's content with a `key` (the file name without
   * its extension) mixed in. A document whose content is *not* an object — a
   * plain text file, or YAML that did not parse — becomes `{key, text}` rather
   * than being skipped: losing a row silently is worse than a row that looks
   * different, and the program can see which is which.
   *
   * One round trip per document. For a big folder, read what you show.
   */
  rows: async function (folder) {
    const files = await vance.documents.list(folder);
    const out = [];
    for (const f of files) {
      const content = await vance.documents.read(f.path);
      if (content && typeof content === 'object' && !Array.isArray(content)) {
        out.push(Object.assign({ key: f.key }, content));
      } else {
        out.push({ key: f.key, text: content == null ? '' : String(content) });
      }
    }
    return out;
  },

  /**
   * Write one record of a folder-as-table back, by its key.
   *
   * `key` is dropped from what is stored: it *is* the file name, so keeping a
   * copy inside the document would be a second truth that can disagree.
   */
  save: async function (folder, record, extension) {
    const key = record && record.key;
    if (!key) throw new Error('core.save needs a record with a `key`.');
    const body = Object.assign({}, record);
    delete body.key;
    await vance.documents.write(core.join(folder, key + (extension || '.yaml')), body);
  },

  /** Remove one record of a folder-as-table by its key. */
  remove: async function (folder, key, extension) {
    await vance.documents.delete(core.join(folder, key + (extension || '.yaml')));
  },

  /** Join path parts without doubling or dropping a slash. */
  join: function (a, b) {
    return String(a).replace(/\/+$/, '') + '/' + String(b).replace(/^\/+/, '');
  },

  // ── state ──────────────────────────────────────────────────────────

  /**
   * Set several state keys at once.
   *
   * `await core.set({rows: r, status: 'ok'})` instead of one awaited call per
   * key — which is the single most repeated shape in every program written so
   * far. Sequential on purpose: the widgets read the same object, and a burst
   * of parallel writes buys nothing but a harder-to-read stack.
   */
  set: async function (values) {
    for (const key of Object.keys(values || {})) {
      await vance.state.set(key, values[key]);
    }
  },

  /** Read several state keys at once, as an object. */
  get: async function () {
    const out = {};
    for (let i = 0; i < arguments.length; i++) {
      out[arguments[i]] = await vance.state.get(arguments[i]);
    }
    return out;
  },

  // ── lists ──────────────────────────────────────────────────────────

  /**
   * Rows whose given fields contain `needle`, case-insensitively.
   *
   * With no fields, every field of the row is searched. An empty needle
   * returns the rows unchanged — a filter box that has not been typed into
   * must not hide anything.
   */
  filter: function (rows, needle, fields) {
    const q = String(needle == null ? '' : needle).trim().toLowerCase();
    if (!q) return rows || [];
    return (rows || []).filter(function (row) {
      const keys = fields && fields.length ? fields : Object.keys(row || {});
      for (const k of keys) {
        const v = row[k];
        if (v != null && String(v).toLowerCase().indexOf(q) >= 0) return true;
      }
      return false;
    });
  },

  /**
   * Rows sorted by one field, ascending unless `descending`.
   *
   * Numeric when both values are numbers — so an amount column sorts 9 before
   * 77 — and textual otherwise. Empty values sort last in both directions: an
   * empty cell is the absence of a value, not the smallest one, and a column
   * of blanks at the top hides the data. Returns a copy; the array you passed
   * is not touched.
   */
  sort: function (rows, field, descending) {
    return (rows || []).slice().sort(function (a, b) {
      return core.compare(a ? a[field] : null, b ? b[field] : null, descending);
    });
  },

  /**
   * The comparison `core.sort` uses. Exposed because a custom sort wants it.
   *
   * The direction goes *inside* — writing `factor * compare(a, b)` reverses the
   * empty-last rule along with everything else, so a descending column puts
   * every blank at the top. That was a real bug here and in the `table` widget,
   * found by the test below rather than by reading the code.
   */
  compare: function (a, b, descending) {
    const ea = a === undefined || a === null || a === '';
    const eb = b === undefined || b === null || b === '';
    if (ea || eb) return ea && eb ? 0 : ea ? 1 : -1;
    const na = Number(a);
    const nb = Number(b);
    const c = !isNaN(na) && !isNaN(nb) ? na - nb : String(a).localeCompare(String(b));
    return descending ? -c : c;
  },

  // ── paging ─────────────────────────────────────────────────────────

  /**
   * The object a `pagination` widget binds to.
   *
   * `page` is **zero-based**, because that is what a slice wants. Pass the
   * previous paging object to keep the reader where they were — clamped, so a
   * shrinking list cannot leave them on a page that no longer exists.
   */
  paging: function (rows, pageSize, previous) {
    const size = Math.max(1, Number(pageSize) || 20);
    const total = (rows || []).length;
    const pages = Math.max(1, Math.ceil(total / size));
    const wanted = previous ? Number(previous.page) || 0 : 0;
    return {
      page: Math.min(Math.max(0, wanted), pages - 1),
      pageSize: size,
      totalCount: total,
    };
  },

  /** The slice of `rows` that `paging` describes. */
  page: function (rows, paging) {
    const p = paging || {};
    const size = Math.max(1, Number(p.pageSize) || 20);
    const from = (Number(p.page) || 0) * size;
    return (rows || []).slice(from, from + size);
  },

  // ── formatting ─────────────────────────────────────────────────────
  //
  // Deliberately thin. A currency, a locale and a date format are decisions
  // about *your* data, and a shared library making them for you is how a
  // helper becomes something to work around.

  /** A number with a fixed number of decimals, or `''` for a non-number. */
  num: function (value, digits) {
    const n = Number(value);
    if (value === null || value === undefined || value === '' || isNaN(n)) return '';
    return n.toFixed(digits === undefined ? 2 : digits);
  },

  /**
   * A date as the reader's locale writes it. Anything unparseable comes back
   * unchanged — showing the raw value beats showing "Invalid Date".
   */
  date: function (value) {
    if (!value) return '';
    const d = new Date(value);
    return isNaN(d.getTime()) ? String(value) : d.toLocaleDateString();
  },

  // ── talking to the reader ──────────────────────────────────────────

  /** `vance.ui.notify`, with the severity spelled out at the call site. */
  say: async function (text) {
    await vance.ui.notify(String(text));
  },

  warn: async function (text) {
    await vance.ui.notify(String(text), 'warn');
  },
};
