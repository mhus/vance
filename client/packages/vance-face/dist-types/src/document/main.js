import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { ensureAuthenticated } from '@vance/shared';
import DocumentApp from './DocumentApp.vue';
import '@/style/app.css';
await ensureAuthenticated();
createApp(DocumentApp).use(createPinia()).mount('#app');
//# sourceMappingURL=main.js.map