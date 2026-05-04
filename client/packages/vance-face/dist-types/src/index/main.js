import { createApp } from 'vue';
import { createPinia } from 'pinia';
import IndexApp from './IndexApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
createApp(IndexApp).use(i18n).use(createPinia()).mount('#app');
//# sourceMappingURL=main.js.map