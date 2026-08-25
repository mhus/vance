// ui@1 — shaping the rendered view from the program.
//
// Requires nothing.
//
// `vance.view.patch(id, changes)` is already one call, so this library is not
// here to shorten it. It is here for the shapes that are several calls, or that
// are easy to build wrongly:
//
//   ui.options('customer', rows, 'key', 'name')
//
// A select whose choices come from data is the case that keeps coming up, and it
// is the one I first tried to answer with a schema key (`optionsFrom:`). That was
// wrong — a framework cannot know which properties an author wants to vary — and
// it is right here: a function that turns rows into choices, in a library nobody
// has to load.
//
// The boundary of the patch API holds: **appearance and presence, never
// behaviour.** Nothing here touches `on:` or `from:`, because a program that
// could rewire which function a button calls would make the document stop
// describing the app.

const ui = {

  /** Hide one widget, or several. Ids as arguments or as one array. */
  hide: function () {
    return ui.each(arguments, function (id) { return vance.view.patch(id, { hide: true }); });
  },

  /** Show one widget, or several — undoes `hide`, not the view's own `show:`. */
  show: function () {
    return ui.each(arguments, function (id) { return vance.view.patch(id, { hide: false }); });
  },

  /** `ui.toggle(id, condition)` — the branch written once instead of twice. */
  toggle: function (id, visible) {
    return vance.view.patch(id, { hide: !visible });
  },

  label: function (id, text) {
    return vance.view.patch(id, { label: String(text == null ? '' : text) });
  },

  text: function (id, value) {
    return vance.view.patch(id, { text: String(value == null ? '' : value) });
  },

  /**
   * A select's choices, from data.
   *
   * ```js
   * ui.options('customer', await customers.all(), 'key', 'name');
   * ui.options('status', ['open', 'paid']);
   * ```
   *
   * With no field names, a row that is a string is its own value and label; an
   * object falls back to `value`/`label`, then `key`/`title`/`name`. Duplicate
   * values are dropped — a select with two identical values cannot report which
   * one was picked.
   */
  options: function (id, rows, valueField, labelField) {
    return vance.view.patch(id, { options: ui.toOptions(rows, valueField, labelField) });
  },

  /** The same, for one field of a `form` or `details`. */
  fieldOptions: function (formId, name, rows, valueField, labelField) {
    return ui.field(formId, name, { options: ui.toOptions(rows, valueField, labelField) });
  },

  /** Rows to `{value, label}` pairs. Exposed because a custom patch wants it. */
  toOptions: function (rows, valueField, labelField) {
    const out = [];
    const seen = {};
    for (const row of rows || []) {
      let value;
      let label;
      if (row === null || row === undefined) continue;
      if (typeof row !== 'object') {
        value = String(row);
        label = value;
      } else {
        value = row[valueField || 'value'];
        if (value === undefined && !valueField) {
          value = row.key !== undefined ? row.key : row.id;
        }
        label = row[labelField || 'label'];
        if (label === undefined && !labelField) {
          label = row.title !== undefined ? row.title
            : (row.name !== undefined ? row.name : value);
        }
      }
      if (value === undefined || value === null) continue;
      const key = String(value);
      if (seen[key]) continue;
      seen[key] = true;
      out.push({ value: key, label: String(label === undefined ? key : label) });
    }
    return out;
  },

  // ── fields of a form ───────────────────────────────────────────────

  /** One field: `{label, help, hide, required, options}`. */
  field: function (formId, name, patch) {
    const fields = {};
    fields[name] = patch;
    return vance.view.patch(formId, { fields: fields });
  },

  /** Several at once: `ui.fields('f', {amount: {hide: true}, note: {label: 'Why'}})` */
  fields: function (formId, patches) {
    return vance.view.patch(formId, { fields: patches || {} });
  },

  /** `ui.hideFields('f', ['amount', 'note'])` */
  hideFields: function (formId, names) {
    return ui.applyToFields(formId, names, { hide: true });
  },

  showFields: function (formId, names) {
    return ui.applyToFields(formId, names, { hide: false });
  },

  /** `ui.required('f', ['customer'], true)` */
  required: function (formId, names, required) {
    return ui.applyToFields(formId, names, { required: required !== false });
  },

  applyToFields: function (formId, names, patch) {
    const fields = {};
    for (const name of ui.list(names)) fields[name] = patch;
    return vance.view.patch(formId, { fields: fields });
  },

  // ── undoing ────────────────────────────────────────────────────────

  /**
   * Drop patches: one widget's, or every one.
   *
   * The document is untouched by any of this, so a reset is the way back to
   * exactly what the view says — which is why the patches live beside the tree
   * and not in it.
   */
  reset: function (id) {
    return vance.view.reset(id);
  },

  /** The raw call, for a change with no helper here. */
  patch: function (id, changes) {
    return vance.view.patch(id, changes);
  },

  // ── plumbing ───────────────────────────────────────────────────────

  /** One value, an array, or an arguments object — always an array. */
  list: function (value) {
    if (value === null || value === undefined) return [];
    if (Array.isArray(value)) return value;
    if (typeof value === 'object' && typeof value.length === 'number') {
      return Array.prototype.slice.call(value);
    }
    return [value];
  },

  /**
   * Apply to each id and await them together.
   *
   * `Promise.all` rather than sequentially: these are host calls with no
   * ordering between them, and hiding six widgets one round trip at a time is
   * visible as a flicker.
   */
  each: function (ids, fn) {
    const flat = [];
    for (const item of ui.list(ids)) {
      for (const id of ui.list(item)) flat.push(id);
    }
    return Promise.all(flat.map(fn));
  },
};
