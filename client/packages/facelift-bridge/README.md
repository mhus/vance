# @vance/facelift-bridge

Thin Capacitor wrapper around the deployed Vance web UI. The bundle
itself contains only an **account picker** — when the user taps an
entry, the WebView navigates to the configured Brain URL (e.g.
`https://eddie.mhus.de`) via `window.location.href`, and the live
website takes over from there. Auth, editors, and all REST/WS traffic
happen inside the website exactly as in a desktop browser.

Replaces `@vance/vance-fingers` over the medium term — see
`planning/vance-facelift.md` for the architecture and phasing.

## Why this shape

We did **not** rebuild the editor stack natively, and we do **not**
bundle the website's `dist/` into the app. The website is a moving
target (new editors, new addons), and shipping the bundle through
App Store review every time we add a feature would make Vance
unshippable. The wrapper is intentionally tiny and never needs to
know about Chat, Inbox, or Documents — those are the website's
problem.

What the wrapper adds on top of just "open Safari to
`eddie.mhus.de`":

- **Multi-Identity** — one row per Brain server. Each `brainUrl` is
  its own WebView origin, so iOS keeps cookies, IndexedDB and Service
  Workers separated automatically.
- **App-Store distribution** — appears in the iOS App Store with its
  own icon, push entitlement, share-target, etc. (Push and share are
  Phase 5 — see planning doc.)
- **Native-bridge runway** — later phases inject `window.Capacitor`-
  based plugins (camera, voice, secure-storage) that the website can
  feature-detect.

## v1 Scope

- Capacitor 6 + Vite + Vue 3 + Vue Router 4
- Picker (`/#/`) + Add-Account (`/#/add`) screens
- Account list persisted in `@capacitor/preferences` —
  `{ id, brainUrl, displayName, createdAt, lastUsedAt }`
- iOS only (Android scaffolding deferred)

Explicit non-features in v1:

- No native auth — the website handles login in the WebView. No
  Keychain integration in the wrapper (the wrapper holds no tokens).
- No sign-out from the wrapper — the user signs out inside the
  website. To switch accounts, kill and re-open the app (the
  WebView cold-starts back to the picker), or wait until Phase 2
  adds a "Back to picker" bridge plugin.
- No push, no voice, no share — Phase 5+.
- Multiple users on the **same** Brain origin share cookies; only
  one is signed in at a time. Phase 2 considers per-account
  `WKWebsiteDataStore` profiles.

## One-time iOS setup

```bash
cd repos/vance/client/packages/facelift-bridge
pnpm install              # if not already done by wb build facelift
pnpm build                # vite build → dist/
pnpm cap:add:ios          # generates ios/ Xcode workspace
pnpm cap:sync             # copies dist/ + plugins into ios/
pnpm cap:open:ios         # opens Xcode
```

In Xcode: select a Simulator (e.g. iPhone 15), press Run. The
wrapper boots straight into the empty picker — add an account
pointing at any reachable Brain URL.

For dev-loop iteration:

```bash
# Terminal 1: Vite dev-server on :9901 (serves the picker itself)
pnpm dev

# Terminal 2: live-reload-build the iOS app against the dev-server
pnpm cap:run:ios
```

## File map

```
src/
├── main.ts                       Entry — creates Vue app, mounts router
├── App.vue                       Root — just a <router-view>
├── router.ts                     Hash history, two routes (picker, add)
├── style.css                     Tailwind base + safe-area handling
├── accounts/
│   └── accountStore.ts           Account[] CRUD over Capacitor Preferences
└── views/
    ├── PickerView.vue            Account list, tap → window.location.href
    └── AddAccountView.vue        Brain-URL + display-name form
```

## Why no `server.url` in capacitor.config.ts

We deliberately ship the picker as bundled assets (`webDir: 'dist'`)
rather than setting `server.url` to a remote Brain. With
`server.url`, **every** WebView session would start at that one URL —
killing the multi-identity story before it begins. Bundling the picker
means cold-starts always land on `capacitor://localhost/index.html`
and the user explicitly chooses which Brain to open.
