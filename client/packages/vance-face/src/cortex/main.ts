import '@/platform/bootWeb';
import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { initAddonRemotes } from '@/platform/addonRegistry';
import { registerBuiltInKinds } from '@/document/builtInKinds';
import EditorApp from './EditorApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

await ensureAuthenticated();
// Built-in kinds register synchronously; addon kinds do not register here at
// all any more. `initAddonRemotes` only makes the remotes *addressable* and
// indexes which addon owns which kind — the fetch happens when a document of
// that kind is opened (cortexStore.openFile → ensureKindsForDocument).
registerBuiltInKinds();
await initAddonRemotes();
createApp({
  render: () => h(EditorApp),
})
  .use(i18n)
  .use(createPinia())
  .mount('#app');
