import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import AppEditor from './AppEditor.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
createApp(AppEditor).use(i18n).use(createPinia()).mount('#app');
//# sourceMappingURL=main.js.map