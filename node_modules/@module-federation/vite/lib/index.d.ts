import { moduleFederationPlugin } from "@module-federation/sdk";
import { ShareStrategy } from "@module-federation/runtime/types";

//#region src/utils/normalizeModuleFederationOptions.d.ts
interface RemoteObjectConfig {
  type?: string;
  name: string;
  internalName?: string;
  entry: string;
  entryGlobalName?: string;
  shareScope?: string;
}
interface PluginManifestOptions {
  filePath?: string;
  disableAssetsAnalyze?: boolean;
  fileName?: string;
  additionalData?: (options: {
    stats: Record<string, unknown>;
    manifest?: Record<string, unknown>;
    pluginOptions: Record<string, unknown>;
    compiler?: unknown;
    compilation?: unknown;
    bundler: 'vite';
  }) => Promise<Record<string, unknown> | void> | Record<string, unknown> | void;
}
type ModuleFederationOptions = {
  exposes?: Record<string, string | {
    import: string;
  }> | undefined;
  filename?: string;
  library?: any;
  name: string;
  remotes?: Record<string, string | RemoteObjectConfig> | undefined;
  runtime?: any;
  shareScope?: string;
  /**
   * Override the public path used for remote entries
   * Defaults to Vite's base config or "auto" if base is empty
   */
  publicPath?: string;
  /**
   * Controls whether all CSS assets from the bundle should be added to every exposed module.
   * When false (default), the plugin will not process any CSS assets.
   * When true, all CSS assets are bundled into every exposed module.
   */
  bundleAllCSS?: boolean;
  shared?: string[] | Record<string, string | {
    name?: string;
    version?: string;
    shareScope?: string;
    singleton?: boolean;
    requiredVersion?: string;
    strictVersion?: boolean;
    import?: moduleFederationPlugin.SharedConfig['import'];
  }> | undefined;
  runtimePlugins?: Array<string | [string, Record<string, unknown>]>;
  getPublicPath?: string;
  implementation?: string;
  manifest?: PluginManifestOptions | boolean;
  dev?: boolean | PluginDevOptions;
  dts?: boolean | PluginDtsOptions;
  shareStrategy?: ShareStrategy;
  ignoreOrigin?: boolean;
  virtualModuleDir?: string;
  hostInitInjectLocation?: HostInitInjectLocationOptions;
  /**
   * Timeout for parsing modules in seconds.
   * Defaults to 10 seconds.
   */
  moduleParseTimeout?: number;
  /**
   * Idle timeout for parsing modules in seconds. When set, the timeout resets
   * on every parsed module and only fires when there has been no module activity
   * for the configured duration. Prefer this over `moduleParseTimeout` for large
   * codebases where the total build time may exceed the fixed timeout.
   */
  moduleParseIdleTimeout?: number;
  /**
   * Allows generate additional remoteEntry file for "var" host environment
   */
  varFilename?: string;
  /**
   * Target environment for the build to enable effective tree-shaking.
   *
   * @see https://module-federation.io/configure/experiments#target
   * @default 'web' (or 'node' if build.ssr is enabled)
   */
  target?: 'web' | 'node';
  /**
   * Additional packages to mark as external in the SSR remote entry build.
   * Shared packages and MF runtime packages are always external. Use this to
   * add any other Node-only packages that should not be bundled into the SSR entry.
   */
  ssrExternals?: string[];
};
type HostInitInjectLocationOptions = 'entry' | 'html';
interface PluginDevOptions {
  disableLiveReload?: boolean;
  disableHotTypesReload?: boolean;
  disableDynamicRemoteTypeHints?: boolean;
  /**
   * Controls cross-federation HMR for remote modules.
   *
   * - `false` / `undefined` — HMR disabled (default).
   * - `true` — HMR enabled with auto-detected strategy. When a framework
   *   plugin with cross-federation HMR support is detected, broadcast/relay
   *   is suppressed and the framework's native HMR handles updates:
   *     - React (`@vitejs/plugin-react` / `@vitejs/plugin-react-swc`) — the
   *       plugin serves a `/@react-refresh` proxy on remotes that delegates
   *       to the host's `RefreshRuntime`, unifying the component registry.
   *     - Vue (`@vitejs/plugin-vue` / `@vitejs/plugin-vue-jsx`) — the plugin
   *       injects a `__VUE_HMR_RUNTIME__` guard into the host page so the
   *       first-loaded (host) Vue runtime is pinned and remote-loaded Vue
   *       copies cannot overwrite it.
   *   For any host, the plugin also injects a script that clears the
   *   federation `moduleCache` on `vite:beforeUpdate` so subsequent
   *   `loadRemote()` calls return the freshly patched module.
   *   Other frameworks fall back to full page reloads.
   * - `'full-reload'` — HMR enabled, always use full page reloads even when
   *   a framework with native cross-federation HMR is detected.
   */
  remoteHmr?: boolean | 'full-reload';
}
interface RemoteTypeUrl {
  alias?: string;
  api: string;
  zip: string;
}
interface RemoteTypeUrls {
  [remoteName: string]: RemoteTypeUrl;
}
interface PluginDtsOptions {
  generateTypes?: boolean | DtsRemoteOptions;
  consumeTypes?: boolean | DtsHostOptions;
  tsConfigPath?: string;
  extraOptions?: Record<string, unknown>;
  implementation?: string;
  cwd?: string;
  displayErrorInTerminal?: boolean;
}
interface DtsRemoteOptions {
  tsConfigPath?: string;
  typesFolder?: string;
  compiledTypesFolder?: string;
  deleteTypesFolder?: boolean;
  additionalFilesToCompile?: string[];
  compilerInstance?: 'tsc' | 'vue-tsc' | 'tspc' | string;
  compileInChildProcess?: boolean;
  generateAPITypes?: boolean;
  extractThirdParty?: boolean | {
    exclude?: Array<string | RegExp>;
  };
  extractRemoteTypes?: boolean;
  abortOnError?: boolean;
  deleteTsConfig?: boolean;
}
interface DtsHostOptions {
  typesFolder?: string;
  abortOnError?: boolean;
  remoteTypesFolder?: string;
  deleteTypesFolder?: boolean;
  maxRetries?: number;
  consumeAPITypes?: boolean;
  runtimePkgs?: string[];
  remoteTypeUrls?: (() => Promise<RemoteTypeUrls>) | RemoteTypeUrls;
  timeout?: number;
  family?: 0 | 4 | 6;
  typesOnBuild?: boolean;
}
//#endregion
//#region src/index.d.ts
declare function federation(mfUserOptions: ModuleFederationOptions): any[];
declare function createModuleFederationConfig<T extends ModuleFederationOptions>(options: T): T;
//#endregion
export { type ModuleFederationOptions, type PluginManifestOptions, createModuleFederationConfig, federation };