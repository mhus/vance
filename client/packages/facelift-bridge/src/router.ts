import { createRouter, createWebHashHistory, type Router } from 'vue-router';
import { isDesktop } from './platform';
import { evaluateLockGate } from './lock/lockStore';
import ShellView from './views/ShellView.vue';
import ManageAccountsView from './views/ManageAccountsView.vue';
import AddAccountView from './views/AddAccountView.vue';
import EditAccountView from './views/EditAccountView.vue';
import LockSetupView from './views/LockSetupView.vue';
import LockUnlockView from './views/LockUnlockView.vue';

/**
 * Hash-based history — the Capacitor WebView serves `index.html`
 * from `capacitor://localhost/`. Hash routing survives WebView
 * reloads and the iOS app switcher returning the user to a deep
 * route.
 */
export function createAppRouter(): Router {
  const router = createRouter({
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
      {
        path: '/lock/setup',
        name: 'lock-setup',
        component: LockSetupView,
        meta: { skipLockGuard: true },
      },
      {
        path: '/lock/unlock',
        name: 'lock-unlock',
        component: LockUnlockView,
        meta: { skipLockGuard: true },
      },
    ],
  });

  // App-lock gate. The lock screen sits in front of every route — on
  // first launch the user lands on `/lock/setup` (where the PIN can
  // also be declined), on every cold start thereafter on
  // `/lock/unlock`. Once unlocked the flag is in-memory only, so
  // background → foreground stays unlocked but a hard kill re-locks.
  // The lock routes themselves are exempt (meta.skipLockGuard) to
  // avoid redirect loops.
  //
  // Whether "no PIN configured" means setup or an open gate is
  // lockStore's call, not the router's — see evaluateLockGate().
  router.beforeEach(async (to) => {
    // Desktop is a windowed app on a personal machine, not a pocketable
    // device — there is no app-lock, so the PIN/biometric gate is skipped
    // entirely (the lock stays a mobile-only feature).
    if (isDesktop()) return true;
    if (to.meta.skipLockGuard === true) return true;
    switch (await evaluateLockGate()) {
      case 'unlocked':
        return true;
      case 'needs-unlock':
        return { name: 'lock-unlock', query: { next: to.fullPath } };
      case 'needs-setup':
        return { name: 'lock-setup', query: { next: to.fullPath } };
    }
  });

  return router;
}
