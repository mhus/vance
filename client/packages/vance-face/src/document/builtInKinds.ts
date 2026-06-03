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
 *
 * v1: empty. Calendar — the first registry-driven Kind — moved to
 * the {@code vance-addon-brain-calendar} addon in Etappe 2.x and
 * registers itself from there.
 */

export function registerBuiltInKinds(): void {
  // No built-in Kinds drive the registry path right now. Future
  // built-ins land here; first-party addons that migrate keep the
  // function trivially empty until at least one Kind stays in the
  // host bundle.
}
