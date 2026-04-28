import { createApp } from 'vue';
import { createPinia } from 'pinia';
import IndexApp from './IndexApp.vue';
import '@/style/app.css';

createApp(IndexApp).use(createPinia()).mount('#app');
