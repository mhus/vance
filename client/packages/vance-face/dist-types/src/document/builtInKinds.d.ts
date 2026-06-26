/**
 * Bootstrap-time registration of host-built-in document Kinds.
 *
 * The runtime {@code @vance/kind-registry} is the single place
 * DocumentApp.vue looks up a Kind's view + codec for any
 * registry-driven branch. Built-ins land here; addons populate
 * the same registry from their {@code ./register} federation
 * expose. When a Kind moves from built-in to addon, the call
 * below moves verbatim into the addon's register.ts and this file
 * shrinks by one entry — DocumentApp.vue stays unchanged.
 *
 * Only Kinds that DocumentApp.vue dispatches *via the registry*
 * land here. Most built-ins still use the static {@code if/else}
 * dispatch and don't need a registration — they'll migrate as
 * additional addons get carved out.
 */
export declare function registerBuiltInKinds(): void;
//# sourceMappingURL=builtInKinds.d.ts.map