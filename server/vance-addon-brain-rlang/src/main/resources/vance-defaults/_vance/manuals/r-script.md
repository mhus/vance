---
triggers: R, Rscript, R script, R lang, statistics, statistik, dplyr, tidyverse, ggplot, ggplot2, time series, Zeitreihe, forecast, ARIMA, regression, lm(), anova, t-test, Bioconductor, survival analysis, statisticaltest, statistical test
summary: Evaluate an R script on the brain's Rserve daemon and return captured stdout + the final expression's value.
---
# Tool — `r_script`

Run R code on the brain's Rserve daemon. Use this for stats,
data-frame, plotting, forecasting, or any task where R's ecosystem
(`dplyr`, `tidyr`, `ggplot2`, `forecast`, Bioconductor, …) beats
Python's equivalent.

## When to use this

- Statistical tests / models the user names by R's terminology
  (`lm`, `glm`, `lmer`, `t.test`, `aov`, `survreg`).
- Time series with `forecast`, `prophet`, `tsibble`, `fable`.
- Bioinformatics / `Bioconductor` workflows.
- Data wrangling the user has already in tidyverse mental model.
- Plotting with `ggplot2` when the user explicitly asks for R-style
  charts.

If the task is generic numerical (mean, regression on simple data,
toy plot), Python is usually fine and faster to spin up. Reach for
R when its library buys something.

## Parameters

| Parameter | Type | Required | Notes |
|---|---|---|---|
| `script` | string | yes | R code, multi-line OK. Shipped verbatim — quote your strings normally, no escape tricks |
| `workingDir` | string | no | Absolute path for the R session's `cwd`. Set if your script uses `read.csv("local.csv")` / `ggsave("plot.png")` |

## Returns

```
{
  rVersion:      "4.4.2",
  elapsedSec:    0.42,
  contentLength: 234,
  truncated:     false,
  text:          "<combined stdout + final expression>",
  outputs: [                              // only when files were written
    {
      kind:         "image",              // image | svg | pdf | records | data | markdown | text | html | document
      path:         "r-outputs/2026-05-28-200115/plot.png",
      vanceUri:     "vance:/r-outputs/...",
      markdownLink: "![plot.png](vance:/...)",
      size:         12345
    },
    …
  ]
}
```

`text` is `print()` / `cat()` output joined with a newline plus, at
the end, the formatted result of the last expression — exactly what
you'd see in an interactive R session.

Truncated past 50 000 characters; `contentLength` reports full
length so you know if you missed anything.

`outputs` is present **when the script wrote files** into the
working directory (e.g. via `ggsave`, `png()`/`dev.off()`,
`pdf()`, `write.csv`). Each entry is already a Vance Document —
embed it by pasting `markdownLink` verbatim into your answer.

## Examples

### Quick stats

```
r_script(script="
  data <- c(2.1, 3.4, 5.0, 5.2, 6.1, 7.8)
  cat('mean:', mean(data), '\\n')
  cat('sd:  ', sd(data), '\\n')
  summary(data)
")
```

### Linear model

```
r_script(script="
  df <- mtcars
  fit <- lm(mpg ~ wt + hp, data=df)
  summary(fit)
")
```

### Time series forecast

```
r_script(script="
  library(forecast)
  ap <- AirPassengers
  fit <- auto.arima(ap)
  forecast(fit, h=12)
")
```

### With working dir for file I/O

```
r_script(
  script='df <- read.csv("sales.csv"); cat(nrow(df), "rows\\n"); summary(df)',
  workingDir="/path/to/project/scratch/r-runs/2026-05-28/"
)
```

### Plot a chart — auto-imported as Document

```
r_script(script="
  library(ggplot2)
  p <- ggplot(mtcars, aes(wt, mpg, color=factor(cyl))) +
       geom_point(size=3) +
       labs(title='MPG vs. Weight', x='Weight (1000 lbs)', y='Miles/Gallon')
  ggsave('mpg-vs-weight.png', p, width=6, height=4, dpi=120)
")
```

The `outputs` array in the response will carry the new document.
Embed it in your answer by pasting `markdownLink` verbatim:

```
Here's the chart:
![mpg-vs-weight.png](vance:/r-outputs/2026-05-28-200115/mpg-vs-weight.png?kind=image)
```

### Write a CSV — also auto-imported

```
r_script(script="
  library(dplyr)
  summary <- mtcars |> group_by(cyl) |> summarise(avg_mpg=mean(mpg))
  write.csv(summary, 'mpg-by-cyl.csv', row.names=FALSE)
")
```

Returns a `records` document — link it the same way.

## Anti-patterns

- **Don't paste the script into another R expression.** Just put it
  in the `script` parameter as-is. The tool ships it as an R string
  variable — quotes, dollar signs, multi-line, all fine.
- **Don't try to base64-embed plots in the text output.** Just
  call `ggsave("plot.png", …)` — the tool auto-imports the file as
  a Vance Document and gives you back a `markdownLink` to paste
  verbatim. Way cheaper in tokens, way better-looking in the chat.
- **Don't ask the user "should I install package X?"** when an R
  error says *"there is no package called 'X'"*. Just install it
  yourself in the next call:
  ```
  r_script(script="install.packages('ggplot2', repos='https://cran.r-project.org')")
  ```
  Then retry the failed call. Installs persist for the lifetime of
  the Rserve session, so one install per package per host. The
  user wants the result, not a multi-turn permission dialog.
- **Don't `library()` for a package that isn't installed.** The
  brain's Rserve has a base set of packages (the Dockerfile lists
  them); for anything else use `install.packages('pkg')` first.
  Note: the package install lives on the daemon and persists across
  calls, so installing once per session is enough.
- **Don't call this for things Python can do equally well.** R's
  startup is fast (Rserve keeps it warm), but the ecosystem cost
  pays off only when you use R-specific libraries. `mean(c(1,2,3))`
  doesn't justify the round-trip.

## Failure modes

The tool throws `ToolException` for each — the message tells you
which:

| Symptom | Likely cause | Recovery |
|---|---|---|
| "Cannot start Rserve: R is not on PATH" | R isn't installed on the brain host | Operator concern — install R + Rserve package |
| "Rserve startup failed (process exited with N)" | R is there but the Rserve package isn't, or the port is taken | Operator concern |
| "Rserve did not become reachable in Xs" | Daemon spawned but never opened the port within the timeout | Operator concern |
| "Rserve not reachable … autostart=false" | Auto-start disabled and external daemon down | Operator concern |
| "R error: <message>" | The R script raised a condition | Read the message — it's the same string `conditionMessage(e)` returns in R |
| "setwd('X') failed" | The requested `workingDir` doesn't exist or isn't writable | Check the path; fall back to omitting `workingDir` |
| "Could not open Rserve connection" | Daemon was running but TCP refused mid-call (crashed) | Tool will respawn on next call automatically |

## Library availability

Base R + recommended packages always available. Beyond that the
brain's Rserve image ships:

- `tidyverse` (dplyr, tidyr, ggplot2, readr, …)
- `forecast`
- `data.table`

Anything else: `install.packages('pkg', repos='https://cran.r-project.org')`
once per Rserve session.

## Not in this iteration

- **Persistent project-scoped R workspace.** Right now every call is
  a fresh Rserve fork (R-side packages persist across calls because
  Rserve caches them, but assigned variables don't). The
  RLangHandler in iteration 2 will give you a per-project named
  workspace that holds state.
- **Streaming progress mid-script.** The tool emits one "starting"
  and one "done" ping; long-running scripts look silent in between.
  Iteration 2 will add per-statement progress.
