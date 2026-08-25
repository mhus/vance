// fmt@1 — formatting, with the policy that `core@1` refuses to carry.
//
// Requires nothing.
//
// `core.num` and `core.date` are deliberately thin: a shared *core* that picks
// a currency or a date pattern for you becomes something to work around. Here
// that objection does not apply — nobody loads this library by accident, so it
// can make decisions. Every one of them is a documented default that an app
// overrides with `fmt.setup()`.
//
// Everything below is `Intl` underneath. What the library adds is a decision
// per question ("does percent take 0.15 or 15") and one place to change it.

const fmt = {

  /**
   * Defaults for every call that does not override them.
   *
   * `locale` starts as the reader's, which is the one honest default: the app
   * does not know where its reader is. `currency` starts as EUR, which is
   * arbitrary — but a wrong currency symbol is visible in the first row, unlike
   * a wrong decimal separator, so an arbitrary default here is cheap to catch.
   *
   * `fmt.setup({ currency: 'CHF', locale: 'de-CH' })` in `init()`.
   */
  locale: (typeof navigator !== 'undefined' && navigator.language) || 'en',
  currency: 'EUR',
  /** 1024 rather than 1000 — see `bytes`. */
  byteBase: 1024,

  setup: function (options) {
    for (const key of Object.keys(options || {})) {
      fmt[key] = options[key];
    }
    return fmt;
  },

  // ── numbers ────────────────────────────────────────────────────────

  /**
   * A number, grouped for the locale.
   *
   * `''` for anything that is not one — including `null` and `''` themselves.
   * A dash or a zero would both be claims about the data that the data did not
   * make.
   */
  number: function (value, digits) {
    const n = fmt.toNumber(value);
    if (n === null) return '';
    const opts = {};
    if (digits !== undefined && digits !== null) {
      opts.minimumFractionDigits = digits;
      opts.maximumFractionDigits = digits;
    }
    return new Intl.NumberFormat(fmt.locale, opts).format(n);
  },

  /** An amount with its currency symbol, in the locale's placement. */
  money: function (value, currency) {
    const n = fmt.toNumber(value);
    if (n === null) return '';
    return new Intl.NumberFormat(fmt.locale, {
      style: 'currency',
      currency: currency || fmt.currency,
    }).format(n);
  },

  /**
   * A **fraction** as a percentage: `fmt.percent(0.15)` is `15 %`.
   *
   * The `Intl` convention, and the one worth following — a library that took 15
   * for 15 % would disagree with every other percent formatter a reader meets.
   * Multiply before calling if your data holds whole percents.
   */
  percent: function (value, digits) {
    const n = fmt.toNumber(value);
    if (n === null) return '';
    return new Intl.NumberFormat(fmt.locale, {
      style: 'percent',
      minimumFractionDigits: digits === undefined ? 0 : digits,
      maximumFractionDigits: digits === undefined ? 0 : digits,
    }).format(n);
  },

  /**
   * A byte count, base **1024** with `KB`/`MB` labels.
   *
   * A choice, not a fact: file managers on Windows and Linux count this way,
   * macOS counts in 1000s, and the strictly correct labels for 1024 are
   * `KiB`/`MiB` which most readers do not recognise. Set `fmt.byteBase = 1000`
   * if your data comes from somewhere that counts that way.
   */
  bytes: function (value, digits) {
    const n = fmt.toNumber(value);
    if (n === null) return '';
    const base = fmt.byteBase;
    const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    let v = Math.abs(n);
    let i = 0;
    while (v >= base && i < units.length - 1) { v = v / base; i++; }
    const d = digits === undefined ? (i === 0 ? 0 : 1) : digits;
    return (n < 0 ? '-' : '') + fmt.number(v, d) + ' ' + units[i];
  },

  /**
   * A number out of text a person typed, in **their** notation.
   *
   * The inverse of `number`, and the reason this library is worth loading for
   * input: a form gives back the string `'1.234,56'` in German and `'1,234.56'`
   * in English, and `Number()` reads the first as 1.234 without complaining.
   * `null` when it is not a number — distinct from `0`, which is one.
   */
  parseNumber: function (text, locale) {
    if (typeof text === 'number') return isNaN(text) ? null : text;
    let s = String(text == null ? '' : text).trim();
    if (!s) return null;
    // Which separator is the decimal one is a property of the locale, not of
    // the string: '1.234' is a thousand in German and one-point-two in English.
    const parts = new Intl.NumberFormat(locale || fmt.locale).formatToParts(1234.5);
    let group = ',';
    let decimal = '.';
    for (const p of parts) {
      if (p.type === 'group') group = p.value;
      if (p.type === 'decimal') decimal = p.value;
    }
    s = s.split(group).join('');
    // Non-breaking space is a group separator in several locales, and a person
    // pasting a formatted number brings it along.
    s = s.replace(/[  \s]/g, '');
    if (decimal !== '.') s = s.split(decimal).join('.');
    if (!/^[+-]?(\d+(\.\d*)?|\.\d+)$/.test(s)) return null;
    const n = Number(s);
    return isNaN(n) ? null : n;
  },

  /** A number, or `null`. The one place the "what counts as a number" rule lives. */
  toNumber: function (value) {
    if (value === null || value === undefined || value === '') return null;
    if (typeof value === 'number') return isNaN(value) ? null : value;
    const n = Number(value);
    return isNaN(n) ? null : n;
  },

  // ── dates ──────────────────────────────────────────────────────────

  /**
   * A date or time. Styles: `short` (default), `medium`, `long`, `time`,
   * `datetime`, `iso`, `relative`.
   *
   * An unparseable value comes back **unchanged** rather than as
   * "Invalid Date" — showing the raw value at least says what is in the field.
   */
  date: function (value, style) {
    const d = fmt.toDate(value);
    if (d === null) return value == null ? '' : String(value);
    const s = style || 'short';
    if (s === 'iso') return d.toISOString().slice(0, 10);
    if (s === 'relative') return fmt.relative(d);
    const opts = {
      short: { dateStyle: 'short' },
      medium: { dateStyle: 'medium' },
      long: { dateStyle: 'long' },
      time: { timeStyle: 'short' },
      datetime: { dateStyle: 'short', timeStyle: 'short' },
    }[s];
    if (!opts) throw new Error("fmt.date: no style '" + s + "'.");
    return new Intl.DateTimeFormat(fmt.locale, opts).format(d);
  },

  /** "3 days ago", "in 2 hours". `now` is injectable so a test can pin it. */
  relative: function (value, now) {
    const d = fmt.toDate(value);
    if (d === null) return value == null ? '' : String(value);
    const from = now === undefined ? new Date() : fmt.toDate(now);
    const seconds = (d.getTime() - from.getTime()) / 1000;
    const steps = [
      ['year', 31536000], ['month', 2592000], ['week', 604800],
      ['day', 86400], ['hour', 3600], ['minute', 60],
    ];
    const rtf = new Intl.RelativeTimeFormat(fmt.locale, { numeric: 'auto' });
    for (const step of steps) {
      if (Math.abs(seconds) >= step[1]) {
        return rtf.format(Math.round(seconds / step[1]), step[0]);
      }
    }
    return rtf.format(Math.round(seconds), 'second');
  },

  /** A Date, or `null`. Accepts a Date, a number of millis, or a string. */
  toDate: function (value) {
    if (value === null || value === undefined || value === '') return null;
    const d = value instanceof Date ? value : new Date(value);
    return isNaN(d.getTime()) ? null : d;
  },

  /** A span of milliseconds as "2 h 5 min". Coarse on purpose. */
  duration: function (millis) {
    const n = fmt.toNumber(millis);
    if (n === null) return '';
    const total = Math.round(Math.abs(n) / 1000);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    const parts = [];
    if (h) parts.push(h + ' h');
    if (m) parts.push(m + ' min');
    if (s && !h) parts.push(s + ' s');
    return (n < 0 ? '-' : '') + (parts.length ? parts.join(' ') : '0 s');
  },

  // ── text ───────────────────────────────────────────────────────────

  /** "a, b and c", in the locale's grammar. */
  list: function (items, type) {
    const list = (items || []).map(function (i) { return String(i); });
    if (typeof Intl.ListFormat !== 'function') return list.join(', ');
    return new Intl.ListFormat(fmt.locale, { type: type || 'conjunction' }).format(list);
  },

  /** Shortened at the last word boundary that fits, with an ellipsis. */
  truncate: function (text, max) {
    const s = String(text == null ? '' : text);
    const n = max || 80;
    if (s.length <= n) return s;
    const cut = s.slice(0, n);
    const space = cut.lastIndexOf(' ');
    // 0.4, not 0.6: the guard is only there for "no space early enough", and a
    // boundary at 58 % of the budget is plainly the better cut. Found by the
    // test below rather than by reading this line.
    return (space > n * 0.4 ? cut.slice(0, space) : cut).replace(/[\s,.;:]+$/, '') + '…';
  },
};
