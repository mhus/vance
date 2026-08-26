import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { initAddonRemotes } from '@/platform/addonRegistry';
import { registerBuiltInKinds } from './builtInKinds';
import DocumentExplorerApp from './DocumentExplorerApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

// documents.html is the multi-project Explorer — it no longer renders
// document bodies (Cortex does that via the shared EditorApp), and the row
// icon (DocumentIcon.vue) derives from kind + MIME without consulting the
// registry. So no addon kind is needed here; `initAddonRemotes` runs only to
// make the remotes addressable for any `loadRemote` this page reaches.
await ensureAuthenticated();
registerBuiltInKinds();
await initAddonRemotes();
createApp(DocumentExplorerApp).use(i18n).use(createPinia()).mount('#app');
