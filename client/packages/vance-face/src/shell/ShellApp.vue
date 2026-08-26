<script setup lang="ts">
/**
 * The workspace shell. Renders whichever editor the current path names — each
 * route component brings its own `<EditorShell>`, so the topbar and sidebar
 * are still declared where their content is.
 *
 * <p>What this component buys is what it does *not* do on a route change: tear
 * down the Vue app, re-parse the shared bundle, re-run the auth check, or drop
 * the WebSocket. See shell/main.ts for the boot it owns once.
 *
 * <p>It also owns the one piece of chrome that belongs to the session rather
 * than to any editor: the notice that the deployment has moved on underneath
 * it. That case only exists because the session is long now — as separate
 * pages, every navigation picked up a new release for free.
 */
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { VButton } from '@/components';
import { VERSION_DRIFT_EVENT } from './versionWatch';

const newVersion = ref(false);

function onDrift(): void {
  newVersion.value = true;
}

function reload(): void {
  window.location.reload();
}

onMounted(() => window.addEventListener(VERSION_DRIFT_EVENT, onDrift));
onBeforeUnmount(() => window.removeEventListener(VERSION_DRIFT_EVENT, onDrift));
</script>

<template>
  <!-- An offer, never an act. Reloading on its own would take a half-written
       chat turn or an unsaved editor with it, and the reader has no way to see
       it coming. Dismissible for the same reason — the announcement fires once
       per session, so "Later" means later, not never-asked. -->
  <div
    v-if="newVersion"
    class="fixed inset-x-0 top-0 z-50 flex items-center justify-center gap-3
           bg-primary px-4 py-2 text-sm text-primary-content shadow"
    role="status"
  >
    <span>{{ $t('newVersion.message') }}</span>
    <VButton size="sm" @click="reload">
      {{ $t('newVersion.action') }}
    </VButton>
    <button
      type="button"
      class="text-xs underline underline-offset-2 opacity-80 hover:opacity-100"
      @click="newVersion = false"
    >{{ $t('newVersion.dismiss') }}</button>
  </div>

  <RouterView />
</template>
