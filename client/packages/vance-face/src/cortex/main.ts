import '@/platform/bootWeb';
import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { loadAddonRegistrations } from '@/platform/loadAddonRegistrations';
import { registerBuiltInKinds } from '@/document/builtInKinds';
import EditorApp from './EditorApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// Same Kind-Registry bootstrap as document/main.ts so addon kinds
// (Calendar, future contributions) register before the first render
// and DocumentTabShell's resolveBinding can dispatch via the registry.
registerBuiltInKinds();
await loadAddonRegistrations();
createApp({
  render: () => h(EditorApp),
})
  .use(i18n)
  .use(createPinia())
  .mount('#app');
