---
triggers: formula, math, latex, KaTeX, mhchem, equation, chem, chemistry, "chemical formula", "math formula", "render equation", "show formula", Formel, chemische Formel, mathematische Formel, Gleichung
summary: Render math or chemistry formulas via KaTeX+mhchem using a ```formula fence (inline) or kind=formula (stored document).
---
# Kind — `formula` (KaTeX + mhchem)

Render mathematical and chemical formulas with KaTeX. The
`formula` kind covers both disciplines — math via standard
LaTeX syntax (`\frac`, `\sqrt`, `\sum`, …) and chemistry via
the mhchem extension (`\ce{…}`).

## Inline form — ` ```formula ` fence

The fence body is **raw LaTeX/KaTeX source**. No YAML, no
front-matter, no markdown — just the formula.

### Display mode (default)

The entire body is rendered as a single display-mode formula:

````markdown
```formula
\frac{-b \pm \sqrt{b^2 - 4ac}}{2a}
```
````

### Mixed mode (`mixed=true`)

When the fence-meta `mixed=true` is set, the body is parsed
for math delimiters (`$…$`, `$$…$$`, `\(…\)`, `\[…\`).
Text between formulas is shown as plain text. Use this for
explanations with embedded formulas:

````markdown
```formula mixed=true
The quadratic equation $ax^2 + bx + c = 0$ has the solution
$$x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}$$
```
````

## Chemistry — `\ce{…}` syntax

The mhchem extension is loaded automatically. Use `\ce{…}` for
chemical formulas and equations:

````markdown
```formula
\ce{2 H2 + O2 -> 2 H2O}
```
````

Common mhchem patterns:

````markdown
```formula
\ce{H2O}
```

```formula
\ce{CO2 + C -> 2 CO}
```

```formula
\ce{Fe^{2+} + 2 e- -> Fe}
```

```formula
\ce{A <=> B}
```
````

## Stored form — `doc_write(kind="formula", …)`

Save a formula as a project document:

```
doc_write(
  kind="formula",
  path="formulas/quadratic.formula",
  content="\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"
)
```

The stored body is **raw LaTeX** — never wrap it in a
` ```formula ` fence. The Cortex editor shows a View/Edit
toggle: View renders the formula with KaTeX, Edit shows the
raw source.

## Supported syntax

| Category | Examples |
|----------|----------|
| Fractions | `\frac{a}{b}`, `\dfrac{a}{b}` |
| Roots | `\sqrt{x}`, `\sqrt[n]{x}` |
| Sums/Integrals | `\sum_{i=1}^{n}`, `\int_0^\infty` |
| Greek letters | `\alpha`, `\beta`, `\pi`, `\Theta` |
| Superscript/Subscript | `x^2`, `x_i`, `x_{ij}` |
| Matrices | `\begin{pmatrix} a & b \\ c & d \end{pmatrix}` |
| Chemistry (mhchem) | `\ce{H2O}`, `\ce{2 H2 + O2 -> 2 H2O}` |
| Arrows | `\to`, `\rightarrow`, `\xrightarrow{text}` |
| Limits | `\lim_{x \to 0}` |

## Anti-patterns

- **Wrapping the stored body in a ` ```formula ` fence.** That
  is the inline-chat form. When you save via `doc_write`, the
  body must be raw LaTeX — no fence.
- **Multiple formulas in one fence without `mixed=true`.** In
  display mode, the entire body is treated as one formula. Use
  `mixed=true` or emit separate fences.
- **LaTeX document commands.** `\documentclass`,
  `\begin{document}`, `\section` — this is a formula renderer,
  not a LaTeX compiler. Use `kind: tex` for structured LaTeX
  documents.
- **MathJax-specific syntax.** KaTeX covers most but not all
  MathJax extensions. Stick to core LaTeX + mhchem.
- **Empty fence.** ` ```formula ``` ` with no body renders
  nothing. Always include formula source.

## When to graduate from inline to stored

- The formula is referenced repeatedly across sessions.
- A collection of formulas belongs together (formula sheet).
- The formula is part of a larger document set.

Then call `doc_write(kind="formula", path="formulas/<name>.formula",
content=<raw LaTeX>)` and embed the returned `markdownLink`.

## Relationship to `kind: tex`

`tex` is for `.tex` **documents** — full LaTeX with
`\section`, `\includegraphics`, etc. `formula` is for
**single formula snippets** without document structure.
Both use KaTeX, but only `formula` includes mhchem.
