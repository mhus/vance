import { createRouter, createWebHashHistory, type Router } from 'vue-router';
import ShellView from './views/ShellView.vue';
import ManageAccountsView from './views/ManageAccountsView.vue';
import AddAccountView from './views/AddAccountView.vue';
import EditAccountView from './views/EditAccountView.vue';

/**
 * Hash-based history — the Capacitor WebView serves `index.html`
 * from `capacitor://localhost/`. Hash routing survives WebView
 * reloads and the iOS app switcher returning the user to a deep
 * route.
 */
export function createAppRouter(): Router {
  return createRouter({
    history: createWebHashHistory(),
    routes: [
      // Default landing = shell. ShellView falls back to its empty
      // state when no active account is set, so first-launch users
      // still get sensibly routed.
      { path: '/', redirect: '/shell' },
      { path: '/shell', name: 'shell', component: ShellView },
      { path: '/manage', name: 'manage', component: ManageAccountsView },
      { path: '/add', name: 'add', component: AddAccountView },
      { path: '/edit/:id', name: 'edit', component: EditAccountView, props: true },
    ],
  });
}
