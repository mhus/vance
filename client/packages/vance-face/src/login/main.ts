import '@/platform/bootWeb';
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import LoginApp from './LoginApp.vue';
import { i18n } from '@/i18n';
import '@/style/app.css';

// No `ensureAuthenticated()` here, obviously — this IS the authentication.
// And no addon manifest, no Kind registry, no WebSocket: the login screen is
// the surface that has to boot when the workspace bundle cannot.
createApp(LoginApp).use(i18n).use(createPinia()).mount('#app');
