<script setup lang="ts">
import { computed } from 'vue';
import { clearAuth, getTenantId, getUsername } from '@vance/shared';

/**
 * A breadcrumb segment. Either a plain string label (immutable, no
 * navigation) or an object with an {@code onClick} handler that turns
 * the segment into a button — used to navigate back to a parent view
 * (e.g. from a process detail back to the owning session).
 */
export type Crumb = string | { text: string; onClick?: () => void };

interface Props {
  /** Page title shown in the topbar. */
  title: string;
  /** Breadcrumb segments left-to-right (e.g. `['Project foo', 'Session bar']`). */
  breadcrumbs?: Crumb[];
  /**
   * Connection-state dot in the topbar. Only chat-editor sets this; REST-only
   * editors omit the prop and the dot is hidden.
   */
  connectionState?: 'connected' | 'connecting' | 'disconnected';
  /**
   * Doubles the default width of the right panel (320px → 640px). Use for
   * editors whose right panel hosts forms (e.g. settings editor).
   */
  wideRightPanel?: boolean;
}

withDefaults(defineProps<Props>(), {
  breadcrumbs: () => [],
  wideRightPanel: false,
});

function crumbText(c: Crumb): string {
  return typeof c === 'string' ? c : c.text;
}
function crumbOnClick(c: Crumb): (() => void) | null {
  return typeof c === 'string' ? null : (c.onClick ?? null);
}

const tenantId = computed<string | null>(() => getTenantId());
const username = computed<string | null>(() => getUsername());

function logout(): void {
  clearAuth();
  window.location.href = '/index.html';
}
</script>

<template>
  <div class="min-h-screen flex flex-col bg-base-200">
    <header class="navbar bg-base-100 shadow-sm border-b border-base-300 px-4 gap-2">
      <!-- Logo doubles as a "home" link back to the editor list. -->
      <a
        href="/index.html"
        class="flex-none font-bold text-lg font-mono no-underline hover:opacity-80"
        title="Back to home"
      >vance</a>

      <div class="flex-1 flex items-center gap-2 text-sm">
        <span class="font-semibold">{{ title }}</span>
        <span v-if="breadcrumbs.length" class="opacity-50">·</span>
        <span class="opacity-70 truncate">
          <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
            <button
              v-if="crumbOnClick(crumb)"
              type="button"
              class="crumb-link"
              @click="crumbOnClick(crumb)?.()"
            >{{ crumbText(crumb) }}</button>
            <span v-else>{{ crumbText(crumb) }}</span>
            <span v-if="idx < breadcrumbs.length - 1" class="mx-1 opacity-40">›</span>
          </template>
        </span>
      </div>

      <div class="flex-none flex items-center gap-3">
        <!-- Editor-specific topbar slot — e.g. the project selector in the
             document editor. Sits between the breadcrumbs and the
             connection/user controls. Keep it compact: a single dropdown
             or short button row. -->
        <div v-if="$slots['topbar-extra']" class="flex items-center">
          <slot name="topbar-extra" />
        </div>

        <span
          v-if="connectionState"
          :class="[
            'inline-block w-2.5 h-2.5 rounded-full',
            connectionState === 'connected' ? 'bg-success' : '',
            connectionState === 'connecting' ? 'bg-warning' : '',
            connectionState === 'disconnected' ? 'bg-error' : '',
          ]"
          :title="`Connection: ${connectionState}`"
        />

        <div class="dropdown dropdown-end">
          <div tabindex="0" role="button" class="btn btn-ghost btn-sm">
            <span class="font-mono text-xs opacity-70">{{ tenantId }}</span>
            <span>·</span>
            <span>{{ username }}</span>
          </div>
          <ul
            tabindex="0"
            class="dropdown-content menu bg-base-100 rounded-box z-[1] mt-2 w-48 p-2 shadow"
          >
            <li><a @click="logout">Sign out</a></li>
          </ul>
        </div>
      </div>
    </header>

    <div class="flex-1 flex min-h-0">
      <aside
        v-if="$slots.sidebar"
        class="w-64 shrink-0 border-r border-base-300 bg-base-100 overflow-y-auto"
      >
        <slot name="sidebar" />
      </aside>

      <main class="flex-1 min-w-0 overflow-y-auto">
        <slot />
      </main>

      <aside
        v-if="$slots['right-panel']"
        :class="[
          'shrink-0 border-l border-base-300 bg-base-100 overflow-y-auto',
          wideRightPanel ? 'w-[40rem]' : 'w-80',
        ]"
      >
        <slot name="right-panel" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.crumb-link {
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  color: inherit;
  font-size: inherit;
}
.crumb-link:hover {
  text-decoration: underline;
  opacity: 1;
}
</style>
