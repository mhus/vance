import '@/platform/bootWeb';
import { createApp, h } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import { loadAddonRegistrations } from '@/platform/loadAddonRegistrations';
import { registerBuiltInKinds } from '@/document/builtInKinds';
import EditorApp from '@/cortex/EditorApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
// Notepad reuses the Cortex editor surface with chat turned off.
// Boot order matches cortex/main.ts so addon kinds (Calendar, future
// contributions) are available before the first render.
await ensureAuthenticated();
registerBuiltInKinds();
await loadAddonRegistrations();
createApp({
    render: () => h(EditorApp, { mode: 'notepad' }),
})
    .use(i18n)
    .use(createPinia())
    .mount('#app');
//# sourceMappingURL=main.js.map