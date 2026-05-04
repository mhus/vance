import { createApp } from 'vue';
import { ensureAuthenticated } from '@vance/shared';
import ProfileApp from './ProfileApp.vue';
import '@/style/app.css';

await ensureAuthenticated();
createApp(ProfileApp).mount('#app');
