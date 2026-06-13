# VanceShareExtension — iOS Share-Extension setup

The wrapper's "Send to Vance" affordance is implemented as a separate
Xcode target. Capacitor doesn't scaffold app extensions, so the
target itself has to be created manually in Xcode once per
freshly-generated `ios/` directory. The source files below are
committed and ready to drop in.

## Files in this template

- `ShareViewController.swift` — the main extension controller
  (`SLComposeServiceViewController` subclass). Reads accounts /
  projects / credentials from the App-Group container, presents
  pickers, POSTs to `POST /brain/{tenant}/share/inbox`.
- `Info.plist` — extension metadata with `NSExtensionPointIdentifier
  = com.apple.share-services` and activation rule that accepts text,
  URLs and web-page shares.
- `VanceShareExtension.entitlements` — same App-Group
  (`group.de.mhus.vance.facelift`) as the App target.
- `MainInterface.storyboard` — single scene wiring the
  storyboard-loaded `ShareViewController`. Required because
  `NSExtensionMainStoryboard` in `Info.plist` points to it.

## One-time Xcode integration

After `pnpm cap:add:ios` (or after manually deleting and recreating
`ios/`), open Xcode and add the target:

1. **Open** `ios/App/App.xcworkspace` in Xcode.
2. **File → New → Target → Share Extension**.
   - Product Name: `VanceShareExtension`
   - Team: same Apple Dev team as the App target.
   - Bundle Identifier: `de.mhus.vance.facelift.shareExtension` (or
     whatever Xcode suggests — keep it under the App's bundle ID
     namespace).
   - Language: Swift. Include: leave defaults.
   - Activate the scheme when prompted.
3. **Replace the generated files** with the ones from this template
   directory:
   - Delete the auto-generated `ShareViewController.swift`,
     `Info.plist`, and `MainInterface.storyboard` in the new target's
     group.
   - Drag `ShareViewController.swift`, `Info.plist`,
     `MainInterface.storyboard`, and
     `VanceShareExtension.entitlements` from this template into the
     target's group, ticking "Copy items if needed" and "Add to
     target: VanceShareExtension".
4. **Target → Signing & Capabilities → + Capability → App Groups** —
   add `group.de.mhus.vance.facelift`. Make sure both the App target
   and the VanceShareExtension target have the same group enabled.
5. **Target → Build Settings → Code Signing Entitlements** — set to
   `VanceShareExtension/VanceShareExtension.entitlements` for both
   Debug and Release.
6. **Target → General → Deployment Info → iOS 17.0** (matches the
   App target's `IPHONEOS_DEPLOYMENT_TARGET`).

Build the **App** scheme (⌘B), then run on the simulator. The Share
Extension piggy-backs on the App's process for installation.

## Testing

Triggering the extension on the simulator:

1. Open Safari in the simulator and navigate to any page.
2. Tap the share icon → scroll to find **Vance** in the activity list.
   - If it's missing: long-press a row → **Edit Actions** → toggle
     **Vance** on.
3. The Compose-style sheet appears. Pick **Account** → choose one of
   the accounts the main app has synced. Pick **Project**. Tap
   **Post**.
4. Open the main Vance app, sign into that account if you haven't,
   and check the project's inbox — the shared URL / text should be
   the most-recent item.

If nothing shows up in the inbox:

- Xcode → Window → Devices and Simulators → pick the simulator →
  Open Console → filter on `VanceShare`. The extension logs every
  POST status code there.
- The wrapper logs `[VanceFacelift] bridge: action=setShareCredentials`
  + `setProjectSnapshot` when the website pushes snapshots. Without
  those, the extension can't find credentials.

## Limitations of v1

- **Text + URL only.** File / image / PDF sharing is not yet wired
  up — the share sheet will not list Vance for those item types.
- **No background refresh of the bearer token.** When the token in
  `credentials.json` expires the extension POST fails silently. The
  main app re-mints on every silent-refresh, so re-opening Vance
  fixes it. A future iteration calls the brain's `/access/{user}`
  with the persisted refresh token from inside the extension.
- **No success/failure UI.** The extension dismisses regardless of
  HTTP status. The console log is the only signal.
