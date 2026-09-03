<script setup lang="ts">
import { markRaw, onMounted, shallowRef } from 'vue';
import { useI18n } from 'vue-i18n';
import type { Component } from 'vue';
import { loadRemote, registerRemotes } from '@module-federation/runtime';
import { EditorShell, VAlert } from '@/components';
import { addonRemoteEntry, addonRemoteName, loadAddonManifest } from '@/platform/addonManifest';

const { t } = useI18n();

const title = shallowRef<string>('Addon');
const area = shallowRef<Component | null>(null);
const error = shallowRef<string>('');
const loading = shallowRef<boolean>(true);

/**
 * Generic host for a federated addon "area": `addon.html?addon=<id>` loads
 * the addon's `./area` expose and mounts it. The area owns its own in-page
 * URL state (query params), so browser back/forward and deep-links work
 * without the host mediating.
 */
onMounted(async () => {
  try {
    const id = new URLSearchParams(window.location.search).get('addon');
    if (!id) {
      error.value = t('cortex.noAddon');
      return;
    }

    // Presence gate: only addons the server reports as installed are loadable.
    // An unreachable manifest reads as an empty list — "not available".
    const entry = (await loadAddonManifest()).find((a) => a.name === id);
    if (!entry) {
      error.value = `Addon '${id}' is not available in this deployment.`;
      return;
    }
    title.value = entry.tile?.label ?? id;

    registerRemotes(
      [{ name: addonRemoteName(id), entry: addonRemoteEntry(id), type: 'module' as const }],
      { force: true },
    );
    const mod = await loadRemote<{ default?: Component } | Component>(
      `${addonRemoteName(id)}/area`,
    );
    const resolved = (mod as { default?: Component })?.default ?? (mod as Component);
    if (!resolved) {
      error.value = `Addon '${id}' does not expose an area.`;
      return;
    }
    area.value = markRaw(resolved);
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e);
  } finally {
    loading.value = false;
  }
});
</script>

<template>
  <EditorShell :title="title">
    <div class="p-4">
      <VAlert v-if="error" variant="error">{{ error }}</VAlert>
      <p v-else-if="loading" class="text-sm opacity-60 p-4">{{ $t('shell.loading') }}</p>
      <component :is="area" v-else-if="area" />
    </div>
  </EditorShell>
</template>
