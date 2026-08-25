---
name: lib-fmt
summary: fmt@1 — formatting with policy: money, dates, percent, bytes, duration, and parseNumber for what a person typed.
triggers: you wrote `@require fmt@1`, a number needs a currency or a locale, a form gives back "1.234,56" as text, or you are about to write a date pattern by hand
---

# `fmt@1`

```js
// @require fmt@1

async function onAppInit() {
  fmt.setup({ locale: 'de-CH', currency: 'CHF' });   // optional
}
```

Requires nothing. **This is the library that carries policy** — a currency, a
locale, a byte base — which `core@1` deliberately refuses to. The objection
there was that a shared *core* deciding for you becomes something to work
around; here nobody loads it by accident, so it can decide.

| | |
|---|---|
| `fmt.number(v, digits?)` | grouped for the locale |
| `fmt.money(v, currency?)` | symbol where the locale puts it |
| `fmt.percent(v, digits?)` | **v is a fraction**: `0.15` → `15 %` |
| `fmt.bytes(v, digits?)` | base **1024**, `KB`/`MB` labels |
| `fmt.duration(millis)` | `2 h 5 min`, coarse on purpose |
| `fmt.date(v, style?)` | `short`/`medium`/`long`/`time`/`datetime`/`iso`/`relative` |
| `fmt.relative(v, now?)` | `vor 3 Tagen` |
| `fmt.parseNumber(text, locale?)` | text a person typed → a number, or `null` |
| `fmt.toNumber(v)` / `fmt.toDate(v)` | the coercions, or `null` |
| `fmt.list(items)` | `a, b und c` |
| `fmt.truncate(text, max?)` | at a word boundary, with `…` |

## `parseNumber` is the reason to load it for input

A form gives back a **string** in the reader's notation. `Number('1.234,56')` is
`NaN` in German — which you would notice. `Number('1.234')` is `1.234`, which you
would not: it looks like a successful parse and is off by a factor of a thousand.

```js
const amount = fmt.parseNumber(form.amount);
if (amount === null) return core.warn('Not a number.');
```

`null`, not `0`, when it is not a number — zero is a number and a real answer.

## Three decisions worth knowing

**`percent` takes a fraction.** The `Intl` convention: `0.15` → `15 %`. Multiply
first if your data holds whole percents. A library that read `15` as `15 %` would
disagree with every other percent formatter its reader meets.

**`bytes` counts in 1024** with `KB`/`MB`. A choice, not a fact — Windows and
Linux file managers count this way, macOS counts in 1000s, and the strictly
correct labels for 1024 (`KiB`) are ones most readers do not recognise. Set
`fmt.byteBase = 1000` if your data comes from somewhere that counts that way.

**An empty value formats as `''`.** Not `0`, not `–`. Both would be claims about
the data that the data did not make. Likewise an unparseable date comes back
**unchanged** rather than as "Invalid Date" — the raw value at least says what is
in the field.

## Defaults

`locale` starts as the reader's (`navigator.language`), because the app does not
know where its reader is. `currency` starts as `EUR`, which is arbitrary — but a
wrong currency symbol shows up in the first row, unlike a wrong decimal
separator, so it is cheap to catch. Override both in `onAppInit()`.
