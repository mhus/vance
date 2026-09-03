# Building Vancetope Capture from source

This archive is the source for the **Vancetope Capture** add-on
(`vance-capture@vancetope.com`). It is submitted because the uploaded
package is bundled and minified by Vite; nothing in it is obfuscated,
and the minifier is esbuild in its default configuration.

## Why the archive is larger than the add-on

The add-on lives in a pnpm monorepo. It depends on one workspace
package, `@vance/shared` (`client/packages/shared`), which has to be
built first. The lockfile at the root describes every workspace member,
so the tree is shipped whole rather than trimmed — a trimmed tree could
not be installed with `--frozen-lockfile`, and reproducing the build
exactly is the point of this archive.

The add-on's own code is in `client/packages/vance-capture/src`.

## Environment

| | |
|---|---|
| Node.js | 24.15.0 (the root `package.json` allows `^22.18.0 \|\| >=24.11.0`) |
| pnpm | 10.19.0 (pinned in the root `package.json` as `packageManager`) |
| OS | none required — the build is plain Node, verified on macOS 15 (arm64) |

No network access is needed beyond the npm registry during `install`,
and no compiler, Java toolchain or native build step is involved.

## Build

From the root of this archive:

```bash
pnpm install --frozen-lockfile
pnpm --filter @vance/shared build
pnpm --filter @vance/vance-capture build
```

The add-on is then at:

```
client/packages/vance-capture/dist/firefox/
```

That directory is what was uploaded, zipped at its root so that
`manifest.json` is the top-level entry.

Do **not** run the root `pnpm build`: it builds every package in the
monorepo, including the web UI, which is unrelated to this add-on and
much slower.

## Notes for review

- **One bundle, three manifests.** `vite.config.ts` builds the code once
  and stamps a per-browser `manifest.json` (`dist/chrome`,
  `dist/firefox`, `dist/safari`). The Firefox manifest is the base
  manifest plus `browser_specific_settings.gecko`. There is no
  Firefox-specific code path; the only engine difference is `browser.*`
  versus `chrome.*`, resolved at runtime in `src/browserApi.ts`.
- **Filenames are not hashed** (`entryFileNames: 'assets/[name].js'`),
  so a diff between two builds shows the code change rather than a
  renamed file.
- **Host permissions are optional and requested at use time.** The
  manifest declares `optional_host_permissions` rather than
  `host_permissions`; the extension asks for access to a site only when
  the user presses the button on that site.
- **The extension talks to one server: the user's own.** The address is
  entered by the user on the options page, together with a token issued
  by their own Vancetope server. There is no default endpoint, no
  telemetry and no third-party request.

## Licence

Business Source License 1.1, converting to AGPL v3 on 2029-06-23. The
full text is in `LICENSE.txt` at the root of this archive.
