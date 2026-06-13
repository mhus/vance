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

## Facelift detection from the website

The Vance Web-UI can detect that it is being hosted inside Facelift
(rather than a plain Safari tab) and adapt its behaviour — surface
mobile-specific entry points, hide affordances that don't make sense
inside the wrapper, etc.

The signal lives in the User-Agent. The native plugin sets
`WKWebViewConfiguration.applicationNameForUserAgent =
"VanceFacelift/0.1.0"`, which iOS appends to the default Safari UA.
So every HTTP request, WebSocket upgrade and `navigator.userAgent`
look from the website carries that suffix.

```ts
// In vance-face / @vance/shared
export function isFacelift(): boolean {
  return /\bVanceFacelift\/(\d+)/.test(navigator.userAgent);
}
```

Same signal is available server-side from the `User-Agent` HTTP
header — useful for adapting auth-cookie flags, returning different
asset bundles, etc.

## Wrapper actions: `vance-facelift://` URL scheme

To trigger native actions *from* the website (e.g. "log out and
return to the account picker"), the website navigates to a custom
URL. The plugin intercepts the navigation, cancels it, and forwards
the URL to the Vue shell which routes the action.

```ts
// From inside the website running in Facelift:
window.location.href = 'vance-facelift://back-to-picker';
```

Supported actions in v1:

| URL | Effect |
|---|---|
| `vance-facelift://back-to-picker` | Dismiss the WebView, navigate the wrapper to the Manage screen. |
| `vance-facelift://add-account` | Dismiss the WebView, open the Add-Account form. |
| `vance-facelift://switch-account` | Open the account switcher bottom-sheet on top of the website. |

Adding new actions: append a `case` in
`facelift-bridge/src/views/ShellView.vue::handleFaceliftUrl()`. The
URL scheme registration in `Info.plist` is a single
`CFBundleURLTypes` entry covering everything under the
`vance-facelift://` namespace, so no extra registration is needed
per action.

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
