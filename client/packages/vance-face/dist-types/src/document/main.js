import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { loadAddonRegistrations } from '@/platform/loadAddonRegistrations';
import { registerBuiltInKinds } from './builtInKinds';
import DocumentExplorerApp from './DocumentExplorerApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
// documents.html is the multi-project Explorer — it no longer renders
// document bodies (Notepad / Cortex do that via the shared EditorApp).
// We still load addon kinds so the document icon / type chip can
// resolve addon-contributed kinds in the file list.
await ensureAuthenticated();
registerBuiltInKinds();
await loadAddonRegistrations();
createApp(DocumentExplorerApp).use(i18n).use(createPinia()).mount('#app');
//# sourceMappingURL=main.js.map