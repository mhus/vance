/**
 * Bootstrap step that drives addon side-effect registration.
 *
 * 1. Fetch {@code /face/addons} — a static JSON file the face
 *    entrypoint snapshots from the brain at boot — to discover
 *    which addons the admin has enabled.
 * 2. For each known addon, dynamic-import its {@code ./register}
 *    federation expose. The expose is optional: addons without
 *    Kind/Hook contributions don't ship one and the import error
 *    ("Module ./register does not exist in container") is swallowed.
 * 3. Call {@code register()} on the resolved module, which writes
 *    into {@code @vance/kind-registry}'s globalThis-backed Map. The
 *    host reads back from the same Map a few statements later in
 *    main.ts when it mounts the editor.
 *
 * Federation imports must be literal strings so the
 * {@code @module-federation/vite} plugin can rewrite them to
 * runtime {@code loadRemote()} calls at build time. The list below
 * mirrors the {@code addonRemotes} map in {@code vite.config.ts} —
 * a new addon needs an entry in both. (The runtime API
 * {@code loadRemote(dynamicString)} would avoid the duplication
 * but lives in an isolated initialisation context and can't see
 * the host's remotes config.)
 */
export declare function loadAddonRegistrations(): Promise<void>;
//# sourceMappingURL=loadAddonRegistrations.d.ts.map