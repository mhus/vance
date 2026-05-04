# @vance/vance-mobile

React-Native + Expo client for Vance. iOS and Android. Phase A — boot adapter only; the rest of the views land in B–F.

> **Spec:** `specification/mobile-ui.md` (Workbench root)
> **Vorbedingung:** `@vance/shared` ist plattform-neutral (Reorg `readme/reorg-webui-to-clean-shared.md` ist durch).

## Erstes Boot — Phase A

```bash
# 1. Workspace-Install vom client-Root (zieht @vance/shared / @vance/generated als Symlinks)
cd repos/vance/client
pnpm install

# 2. Expo-Versions-Sanity (Expo prüft, ob alle SDK-Plugins zur expo-Version passen, und korrigiert)
pnpm --filter @vance/vance-mobile expo:install

# 3. Brain-URL setzen (sonst zeigt die App den "boot failed"-Screen).
#    Simulator: localhost ist OK.
#    Physisches Gerät im selben WLAN: LAN-IP des Laptops.
export VANCE_BRAIN_URL=http://localhost:8080

# 4. Brain parallel starten (eigenes Terminal)
cd ../../server/vance-brain && mvn spring-boot:run

# 5. Expo Dev-Server
cd ../../client && pnpm --filter @vance/vance-mobile start
```

Im Expo-Terminal-UI:
- `i` öffnet den iOS-Simulator (Xcode benötigt)
- `a` öffnet den Android-Emulator (Android Studio benötigt)
- QR-Code mit Expo Go (App Store / Play Store) auf einem physischen Gerät scannen

**Erwartetes Ergebnis:** Bildschirm zeigt
```
Vance Mobile
Phase A — boot adapter ready ✓
```

Falls stattdessen "Boot failed: vance-mobile: brainUrl is not configured" — `VANCE_BRAIN_URL` setzen und neu starten.

## Was Phase A leistet

- pnpm-Workspace-Integration (Metro mit `watchFolders` + `disableHierarchicalLookup`)
- NativeWind 4 + Tailwind 3 in den Build eingebunden (`global.css` + `babel.config.js` + `metro.config.js`)
- TypeScript strict, mit `@/*`-Alias auf `src/*`
- `src/platform/storageNative.ts` — `PlatformStorage`-Adapter (SecureStore + AsyncStorage), mit synchrem KV-Cache nach Preload
- `src/platform/bootNative.ts` — `configurePlatform` aufrufen, Brain-URL aus `Constants.expoConfig.extra.brainUrl` lesen, REST-Config Bearer-Mode (Refresh + onUnauthorized noch Stub bis Phase B)
- `App.tsx` — Boot-Status-Anzeige (Wait-on-Preload, dann "Phase A boots ✓" oder Fehler)

## Was Phase A NICHT leistet

- Kein Login, keine Auth — Phase B
- Kein Navigations-Stack, keine Tabs — Phase C
- Keine Inbox / Documents / Chat — Phase D / E / F
- Keine Voice — Phase F

## Konfigurations-Knoten

| Datei | Zweck |
|---|---|
| `app.config.ts` | Bundle-IDs, Permissions (Mic / Speech), `extra.brainUrl` |
| `babel.config.js` | NativeWind preset |
| `metro.config.js` | pnpm-Workspace + NativeWind-Wrapper |
| `tailwind.config.js` | Tailwind content-Pfade |
| `global.css` | Tailwind-Direktiven |
| `tsconfig.json` | extends `expo/tsconfig.base`, strict, `@/*`-Alias |

## Versionen

`package.json` pinnt Expo SDK 54, RN 0.81, React 19. Wenn Expo eine neuere Variante empfiehlt:

```bash
pnpm --filter @vance/vance-mobile expo:install
```

aktualisiert die Pins auf das, was Expo für die installierte SDK als kompatibel testet.

## Troubleshooting

- **"Cannot find module @vance/shared"**: `pnpm install` im client-Root nicht gelaufen, oder Metro-Cache stale. `pnpm --filter @vance/vance-mobile start --clear` löscht den Cache.
- **iOS-Simulator startet nicht**: Xcode + Command-Line-Tools müssen installiert sein. `xcode-select --install`.
- **Android-Emulator startet nicht**: Android Studio + ein erstelltes AVD. Doku: https://docs.expo.dev/workflow/android-studio-emulator/
- **TypeScript-Fehler in Dependencies**: `pnpm --filter @vance/vance-mobile typecheck` rennt `tsc --noEmit` lokal. Wenn ein Workspace-Sibling ungebaut ist: `pnpm --filter @vance/shared build` zuerst.
