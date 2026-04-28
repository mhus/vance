# Vance Web-UI

TypeScript / Vue 3 / Vite / Tailwind / DaisyUI client for the Vance Brain.

Spec: [`specification/web-ui.md`](../../../specification/web-ui.md) in the workbench root.

## Layout

```
packages/
  generated/   @vance/generated   TypeScript interfaces, generated from vance-api Java DTOs.
  shared/      @vance/shared      Auth, REST client, WS connection — UI-free, framework-agnostic.
  vance-face/  @vance/vance-face  Vue 3 + Vite + Tailwind + DaisyUI MPA (one HTML per editor).
```

## Build

DTOs are generated from Java sources via the `generate-java-to-ts-maven-plugin`. Run the
Java build first so that `packages/generated/src/` is up to date:

```bash
# From the workbench root:
./wb build face
```

That command runs `mvn -pl vance-api install -am` (re-generating DTOs) and then
`pnpm install && pnpm -r build` here.

## Development

```bash
pnpm install
pnpm --filter @vance/vance-face dev
```

The Vite dev server runs on `http://localhost:3000` and proxies API calls to the
local Brain (configurable via `VITE_BRAIN_URL`).
