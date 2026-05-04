import '@/platform/bootWeb';
import { createApp } from 'vue';
import { ensureAuthenticated } from '@/platform/ensureAuthenticatedWeb';
import ProfileApp from './ProfileApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';
await ensureAuthenticated();
createApp(ProfileApp).use(i18n).mount('#app');
//# sourceMappingURL=main.js.map