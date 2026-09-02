import '@/platform/bootWeb';
import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { initAddonRemotes } from '@/platform/addonRegistry';
import { loadCortexMenuContributions } from '@/platform/loadCortexMenu';
import { registerBuiltInKinds } from '@/document/builtInKinds';
import { router } from './router';
import { bindRouter, navigateTo } from '@/platform/navigate';
import { configureVanceNavigate } from '@vance/shared';
import { startVersionWatch } from './versionWatch';
import ShellApp from './ShellApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

/**
 * The workspace boot — once per session instead of once per editor.
 *
 * Everything below used to run in four near-identical `main.ts` files, on
 * every navigation between them: the platform binding, an auth check that can
 * cost a refresh round trip, the Kind registry, the addon manifest, Vue,
 * Pinia, i18n. The WebSocket followed the same fate one layer down.
 *
 * The login screen is deliberately NOT part of this bundle (see login.html):
 * it is the surface that has to work when this one does not.
 */
await ensureAuthenticated();
registerBuiltInKinds();
await initAddonRemotes();
// Reads the same (already fetched) manifest and registers the Cortex menu
// entries addons declare. No remote is loaded — an entry's bundle is fetched
// when it is clicked. See platform/loadCortexMenu.ts.
await loadCortexMenuContributions();

// Tell the shared navigation helper that a router exists, so the editors reach
// each other without a page load. Components that also run on the standalone
// entries never see this and keep navigating normally there.
bindRouter(router);
// Same rendezvous for the federated addons, which cannot import the host's
// navigate module. Without it a Canvas node, a Wiki link or a Desktop card
// would still reload the workspace to reach a document. Bound before the
// remotes register, so nothing navigates through the fallback first.
configureVanceNavigate(navigateTo);
startVersionWatch();

createApp({ render: () => h(ShellApp) })
  .use(i18n)
  .use(createPinia())
  .use(router)
  .mount('#app');
