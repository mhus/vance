import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { loadAddonRegistrations } from '@/platform/loadAddonRegistrations';
import { registerBuiltInKinds } from '@/document/builtInKinds';
import CortexApp from './CortexApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
// Same Kind-Registry bootstrap as document/main.ts so addon kinds
// (Calendar, future contributions) register before the first render
// and DocumentTabShell's resolveBinding can dispatch via the registry.
registerBuiltInKinds();
await loadAddonRegistrations();
createApp(CortexApp).use(i18n).use(createPinia()).mount('#app');
//# sourceMappingURL=main.js.map